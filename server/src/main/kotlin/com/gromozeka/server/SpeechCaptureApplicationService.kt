package com.gromozeka.server

import com.gromozeka.domain.model.SpeechAudioSource
import com.gromozeka.domain.model.SpeechAvailabilityException
import com.gromozeka.domain.model.SpeechAvailabilityFailure
import com.gromozeka.domain.model.SpeechAvailabilityFailure.Code
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.WorkerAudioInput
import com.gromozeka.domain.model.WorkerPermission
import com.gromozeka.domain.model.failSpeechAvailability
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerTargetResolver
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.service.WorkerAudioCaptureClient
import com.gromozeka.domain.service.WorkerAudioCaptureRequest
import com.gromozeka.domain.service.WorkerAudioCaptureResult
import com.gromozeka.domain.service.WorkerCatalogEntry
import com.gromozeka.domain.service.WorkerCatalogService
import com.gromozeka.remote.protocol.RemoteAudioChunk
import com.gromozeka.remote.protocol.RemoteAudioRecording
import com.gromozeka.remote.protocol.SpeechCaptureAvailabilityResponse
import com.gromozeka.remote.protocol.SpeechCaptureStartedResponse
import com.gromozeka.remote.protocol.SpeechCaptureStoppedResponse
import com.gromozeka.remote.protocol.StartSpeechCaptureRequest
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import kotlin.time.Duration.Companion.minutes

internal data class SpeechCaptureSessionOwner(
    val userId: User.Id,
    val connectionId: String,
) {
    init {
        require(connectionId.isNotBlank()) { "Speech capture connection id must not be blank" }
    }
}

@Service
class SpeechCaptureApplicationService(
    private val settingsProvider: SettingsProvider,
    private val aiConfigurationProvider: AiConfigurationProvider,
    private val workerAccessService: WorkerAccessService,
    private val workerCatalogService: WorkerCatalogService,
    private val workerTargetResolver: ConversationRuntimeWorkerTargetResolver,
    private val workerAudioCaptureClient: WorkerAudioCaptureClient,
) {
    private val log = KLoggers.logger(this)
    private val mutex = Mutex()
    private val sessions = mutableMapOf<SpeechCaptureSessionKey, ActiveSpeechCapture>()
    private val terminalSessions = mutableSetOf<SpeechCaptureSessionKey>()
    private val cancelledBeforeStart = mutableMapOf<SpeechCaptureSessionKey, Long>()

    internal suspend fun availability(user: User): SpeechCaptureAvailabilityResponse =
        runCatching {
            val settings = settingsProvider.userProfile.speechSettings.speechToText
            when (settings.audioSource) {
                SpeechAudioSource.CurrentClient -> requireProcessingTarget(user, settings.processingTarget())
                is SpeechAudioSource.WorkerInput -> resolveRoute(user)
            }
        }.fold(
            onSuccess = { SpeechCaptureAvailabilityResponse(available = true) },
            onFailure = { error ->
                if (error is CancellationException) throw error
                SpeechCaptureAvailabilityResponse(
                    available = false,
                    unavailableReason = (error as? SpeechAvailabilityException)?.failure
                        ?: SpeechAvailabilityFailure(Code.UNAVAILABLE, diagnostic = error.message),
                )
            },
        )

    internal suspend fun requireTranscriptionRoute(user: User) {
        requireProcessingTarget(
            user,
            settingsProvider.userProfile.speechSettings.speechToText.processingTarget(),
        )
    }

    internal suspend fun start(
        owner: SpeechCaptureSessionOwner,
        user: User,
        request: StartSpeechCaptureRequest,
    ): SpeechCaptureStartedResponse {
        require(owner.userId == user.id) { "Speech capture owner does not match authenticated user" }
        val route = resolveRoute(user)
        val key = SpeechCaptureSessionKey(owner, request.sessionId)
        val session = ActiveSpeechCapture(route, uuid7())
        mutex.withLock {
            val now = System.nanoTime()
            removeExpiredEarlyCancellations(now)
            if (cancelledBeforeStart.remove(key) != null) {
                error("Speech capture was cancelled before start: ${request.sessionId}")
            }
            check(sessions.putIfAbsent(key, session) == null) {
                "Speech capture session already exists: ${request.sessionId}"
            }
        }

        try {
            val result = workerAudioCaptureClient.execute(
                WorkerAudioCaptureRequest(
                    target = route.worker,
                    command = WorkerAudioCaptureRequest.Command.StartAudio(
                        session.workerSessionId,
                        route.inputId,
                    ),
                )
            )
            require(result.status == WorkerAudioCaptureResult.Status.STARTED) {
                "Worker returned ${result.status} while starting speech capture"
            }
            check(mutex.withLock { sessions[key] === session && key !in terminalSessions }) {
                "Speech capture was cancelled while starting: ${request.sessionId}"
            }
            return SpeechCaptureStartedResponse(request.sessionId)
        } catch (error: CancellationException) {
            cleanupFailedStart(key, session)
            throw error
        } catch (error: Throwable) {
            cleanupFailedStart(key, session)
            throw error
        }
    }

    internal suspend fun stop(
        owner: SpeechCaptureSessionOwner,
        sessionId: String,
    ): SpeechCaptureStoppedResponse {
        val claimed = claimOwned(owner, sessionId)
        try {
            val result = workerAudioCaptureClient.execute(
                WorkerAudioCaptureRequest(
                    target = claimed.session.route.worker,
                    command = WorkerAudioCaptureRequest.Command.Stop(claimed.session.workerSessionId),
                )
            )
            require(result.status == WorkerAudioCaptureResult.Status.AUDIO_CAPTURED) {
                "Worker returned ${result.status} while stopping speech capture"
            }
            val recording = RemoteAudioRecording(
                sessionId = sessionId,
                format = requireNotNull(result.format),
                chunks = listOf(RemoteAudioChunk(sequenceNumber = 0, data = requireNotNull(result.audioData))),
            )
            finishClaim(claimed, removeSession = true)
            return SpeechCaptureStoppedResponse(recording)
        } catch (error: Throwable) {
            finishClaim(claimed, removeSession = false)
            throw error
        }
    }

    internal suspend fun cancel(
        owner: SpeechCaptureSessionOwner,
        sessionId: String,
    ): Boolean {
        val key = SpeechCaptureSessionKey(owner, sessionId)
        val claimed = mutex.withLock {
            val now = System.nanoTime()
            removeExpiredEarlyCancellations(now)
            val session = sessions[key]
            if (session == null) {
                cancelledBeforeStart[key] = now + EARLY_CANCELLATION_TTL.inWholeNanoseconds
                null
            } else {
                check(terminalSessions.add(key)) {
                    "Speech capture terminal operation is already running: $sessionId"
                }
                cancelledBeforeStart.remove(key)
                ClaimedSpeechCapture(key, session)
            }
        }
        if (claimed != null) {
            try {
                cancelWorkerSession(claimed.session.workerSessionId, claimed.session.route.worker)
                finishClaim(claimed, removeSession = true)
            } catch (error: Throwable) {
                finishClaim(claimed, removeSession = false)
                throw error
            }
        }
        return true
    }

    internal suspend fun stopOwnedBy(owner: SpeechCaptureSessionOwner): Int {
        val owned = mutex.withLock {
            cancelledBeforeStart.keys.removeAll { it.owner == owner }
            terminalSessions.removeAll { it.owner == owner }
            sessions.entries
                .filter { it.key.owner == owner }
                .map { it.key to it.value }
                .also { entries -> entries.forEach { sessions.remove(it.first, it.second) } }
        }
        withContext(NonCancellable) {
            owned.forEach { (key, session) ->
                runCatching { cancelWorkerSession(session.workerSessionId, session.route.worker) }
                    .onFailure { error ->
                        log.warn(error) {
                            "Failed to cancel disconnected speech capture: session=${key.clientSessionId} " +
                                "worker=${session.route.worker.workerId.value} error=${error.message}"
                        }
                    }
            }
        }
        return owned.size
    }

    private suspend fun resolveRoute(user: User): SpeechCaptureRoute {
        val settings = settingsProvider.userProfile.speechSettings.speechToText
        val source = settings.audioSource as? SpeechAudioSource.WorkerInput
            ?: failSpeechAvailability(Code.WORKER_AUDIO_SOURCE_REQUIRED)
        workerAccessService.requirePermission(user, source.workerId, WorkerPermission.USE)

        val workers = workerCatalogService.listWorkers()
        val sourceWorker = workers.singleOrNull { it.workerId == source.workerId }
            ?: failSpeechAvailability(Code.SOURCE_WORKER_NOT_FOUND, "worker" to source.workerId.value)
        val sourceIdentity = workerTargetResolver.requireOnline(
            source.workerId,
            ConversationRuntimeCapability.AUDIO_CAPTURE,
        )
        if (sourceWorker.environmentProfile.audioInputs.none { it.id == source.inputId }) {
            failSpeechAvailability(
                Code.AUDIO_INPUT_UNAVAILABLE,
                "worker" to source.workerId.value,
                "input" to source.inputId.value,
            )
        }

        requireProcessingTarget(user, settings.processingTarget(), workers)
        return SpeechCaptureRoute(sourceIdentity, source.inputId)
    }

    private fun UserProfile.SpeechSettings.SpeechToText.processingTarget(): AiExecutionTarget = when (engine) {
        UserProfile.SpeechSettings.SpeechToText.Engine.OPENAI_API ->
            aiConfigurationProvider.resolveAiRuntime(AiRuntimeAssignment.Purpose.SPEECH_TO_TEXT)
                .connection.executionTarget
        UserProfile.SpeechSettings.SpeechToText.Engine.LOCAL_WHISPER -> localWhisper.executionTarget
    }

    private suspend fun requireProcessingTarget(
        user: User,
        target: AiExecutionTarget,
        workers: List<WorkerCatalogEntry>? = null,
    ) {
        if (target !is AiExecutionTarget.Worker) return
        val availableWorkers = workers ?: workerCatalogService.listWorkers()
        val workerId = ConversationRuntimeWorkerId(target.workerId)
        workerAccessService.requirePermission(user, workerId, WorkerPermission.USE)
        if (availableWorkers.none { it.workerId == workerId }) {
            failSpeechAvailability(Code.TRANSCRIPTION_WORKER_NOT_FOUND, "worker" to workerId.value)
        }
        workerTargetResolver.requireOnline(workerId, ConversationRuntimeCapability.AI_REQUEST_RESPONSE)
    }

    private suspend fun claimOwned(
        owner: SpeechCaptureSessionOwner,
        sessionId: String,
    ): ClaimedSpeechCapture = mutex.withLock {
        val key = SpeechCaptureSessionKey(owner, sessionId)
        val session = sessions[key]
            ?: error("Speech capture session not found: $sessionId")
        check(terminalSessions.add(key)) {
            "Speech capture terminal operation is already running: $sessionId"
        }
        ClaimedSpeechCapture(key, session)
    }

    private suspend fun finishClaim(
        claimed: ClaimedSpeechCapture,
        removeSession: Boolean,
    ) = withContext(NonCancellable) {
        mutex.withLock {
            terminalSessions.remove(claimed.key)
            if (removeSession) sessions.remove(claimed.key, claimed.session)
        }
    }

    private suspend fun cleanupFailedStart(
        key: SpeechCaptureSessionKey,
        session: ActiveSpeechCapture,
    ) = withContext(NonCancellable) {
        val claimed = mutex.withLock {
            if (sessions[key] === session && terminalSessions.add(key)) {
                ClaimedSpeechCapture(key, session)
            } else {
                null
            }
        } ?: return@withContext

        try {
            cancelWorkerSession(session.workerSessionId, session.route.worker)
            finishClaim(claimed, removeSession = true)
        } catch (error: Throwable) {
            finishClaim(claimed, removeSession = false)
            log.warn(error) {
                "Failed to cancel speech capture after start failure: " +
                    "session=${key.clientSessionId} worker=${session.route.worker.workerId.value} " +
                    "error=${error.message}"
            }
        }
    }

    private suspend fun cancelWorkerSession(
        sessionId: String,
        worker: ConversationRuntimeWorkerIdentity,
    ) {
        val result = workerAudioCaptureClient.execute(
            WorkerAudioCaptureRequest(
                target = worker,
                command = WorkerAudioCaptureRequest.Command.Cancel(sessionId),
            )
        )
        require(result.status == WorkerAudioCaptureResult.Status.CANCELLED) {
            "Worker returned ${result.status} while cancelling speech capture"
        }
    }

    private fun removeExpiredEarlyCancellations(nowNanos: Long) {
        cancelledBeforeStart.entries.removeAll { it.value <= nowNanos }
    }

    private data class SpeechCaptureSessionKey(
        val owner: SpeechCaptureSessionOwner,
        val clientSessionId: String,
    )

    private data class ActiveSpeechCapture(
        val route: SpeechCaptureRoute,
        val workerSessionId: String,
    )

    private data class ClaimedSpeechCapture(
        val key: SpeechCaptureSessionKey,
        val session: ActiveSpeechCapture,
    )

    private data class SpeechCaptureRoute(
        val worker: ConversationRuntimeWorkerIdentity,
        val inputId: WorkerAudioInput.Id,
    )

    private companion object {
        val EARLY_CANCELLATION_TTL = 3.minutes
    }
}
