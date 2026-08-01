package com.gromozeka.server

import com.gromozeka.domain.model.SpeechAudioSource
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.WorkerAudioInput
import com.gromozeka.domain.model.WorkerPermission
import com.gromozeka.domain.model.ai.AiConnection
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
import com.gromozeka.domain.service.WorkerOperatingSystem
import com.gromozeka.infrastructure.ai.openai.SttService
import com.gromozeka.remote.protocol.AudioTranscriptionResponse
import com.gromozeka.remote.protocol.SpeechCaptureAvailabilityResponse
import com.gromozeka.remote.protocol.SpeechCaptureStartedResponse
import com.gromozeka.remote.protocol.StartSpeechCaptureRequest
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.io.File
import java.util.Locale
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
    private val sttService: SttService,
) {
    private val log = KLoggers.logger(this)
    private val mutex = Mutex()
    private val sessions = mutableMapOf<SpeechCaptureSessionKey, ActiveSpeechCapture>()
    private val cancelledBeforeStart = mutableMapOf<SpeechCaptureSessionKey, Long>()

    internal suspend fun availability(user: User): SpeechCaptureAvailabilityResponse =
        runCatching {
            val settings = settingsProvider.userProfile.speechSettings.speechToText
            when (settings.audioSource) {
                SpeechAudioSource.CurrentClient -> validateClientAudioRoute(user, settings)
                is SpeechAudioSource.WorkerInput -> prepareRoute(resolveRoute(user))
            }
        }.fold(
            onSuccess = { SpeechCaptureAvailabilityResponse(available = true) },
            onFailure = { error ->
                SpeechCaptureAvailabilityResponse(
                    available = false,
                    unavailableReason = error.message ?: "Speech capture is unavailable",
                )
            },
        )

    internal suspend fun requireClientAudioRoute(user: User) {
        val settings = settingsProvider.userProfile.speechSettings.speechToText
        require(settings.audioSource == SpeechAudioSource.CurrentClient) {
            "Client audio upload is unavailable while a Worker audio source is selected"
        }
        validateClientAudioRoute(user, settings)
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
                    command = route.startCommand(session.workerSessionId),
                )
            )
            require(result.status == WorkerAudioCaptureResult.Status.STARTED) {
                "Worker returned ${result.status} while starting speech capture"
            }
            check(mutex.withLock { sessions[key] === session }) {
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
    ): AudioTranscriptionResponse {
        val session = removeOwned(owner, sessionId)
        val result = workerAudioCaptureClient.execute(
            WorkerAudioCaptureRequest(
                target = session.route.worker,
                command = WorkerAudioCaptureRequest.Command.Stop(session.workerSessionId),
            )
        )
        val text = when (session.route) {
            is SpeechCaptureRoute.DirectClaudeCode -> {
                require(result.status == WorkerAudioCaptureResult.Status.TRANSCRIBED) {
                    "Worker returned ${result.status} for direct Claude Code speech capture"
                }
                requireNotNull(result.transcript)
            }
            is SpeechCaptureRoute.RecordAudio -> {
                require(result.status == WorkerAudioCaptureResult.Status.AUDIO_CAPTURED) {
                    "Worker returned ${result.status} for recorded speech capture"
                }
                sttService.transcribe(
                    audioData = requireNotNull(result.audioData),
                    format = requireNotNull(result.format),
                )
            }
        }
        return AudioTranscriptionResponse(text.trim())
    }

    internal suspend fun cancel(
        owner: SpeechCaptureSessionOwner,
        sessionId: String,
    ): Boolean {
        val key = SpeechCaptureSessionKey(owner, sessionId)
        val session = mutex.withLock {
            val now = System.nanoTime()
            removeExpiredEarlyCancellations(now)
            sessions.remove(key)?.also { cancelledBeforeStart.remove(key) } ?: run {
                cancelledBeforeStart[key] = now + EARLY_CANCELLATION_TTL.inWholeNanoseconds
                null
            }
        }
        if (session != null) {
            cancelWorkerSession(session.workerSessionId, session.route.worker)
        }
        return true
    }

    internal suspend fun stopOwnedBy(owner: SpeechCaptureSessionOwner): Int {
        val owned = mutex.withLock {
            cancelledBeforeStart.keys.removeAll { it.owner == owner }
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
            ?: error("Worker speech capture requires a Worker audio source")
        workerAccessService.requirePermission(user, source.workerId, WorkerPermission.USE)

        val workers = workerCatalogService.listWorkers()
        val sourceWorker = workers.singleOrNull { it.workerId == source.workerId }
            ?: error("Speech audio source Worker not found: ${source.workerId.value}")
        val sourceIdentity = workerTargetResolver.requireOnline(
            source.workerId,
            ConversationRuntimeCapability.AUDIO_CAPTURE,
        )

        val claudeConnection = settings.claudeCodeConnection()
        if (
            claudeConnection != null &&
            source.inputId == WorkerAudioInput.SystemDefault.id &&
            claudeConnection.executionTarget == AiExecutionTarget.Worker(source.workerId.value)
        ) {
            requireDirectClaudeTargetReady(sourceWorker, claudeConnection)
            return SpeechCaptureRoute.DirectClaudeCode(
                worker = sourceIdentity,
                connection = claudeConnection,
                language = settings.mainLanguageCode,
            )
        }

        require(sourceWorker.environmentProfile.audioInputs.any { it.id == source.inputId }) {
            "Speech audio input is unavailable on Worker ${source.workerId.value}: ${source.inputId.value}"
        }

        val processingTarget = settings.processingTarget(claudeConnection)
        requireProcessingTarget(user, processingTarget, workers)
        if (claudeConnection != null) {
            requireClaudeForwardingTargetReady(processingTarget, workers, claudeConnection)
        }
        return SpeechCaptureRoute.RecordAudio(
            worker = sourceIdentity,
            inputId = source.inputId,
        )
    }

    private suspend fun prepareRoute(route: SpeechCaptureRoute) {
        if (route !is SpeechCaptureRoute.DirectClaudeCode) return
        val result = workerAudioCaptureClient.execute(
            WorkerAudioCaptureRequest(
                target = route.worker,
                command = WorkerAudioCaptureRequest.Command.PrepareClaudeCodeMicrophone(
                    connection = route.connection,
                    language = route.language,
                ),
            )
        )
        require(result.status == WorkerAudioCaptureResult.Status.PREPARED) {
            "Worker returned ${result.status} while preparing Claude Code speech capture"
        }
    }

    private suspend fun validateClientAudioRoute(
        user: User,
        settings: UserProfile.SpeechSettings.SpeechToText,
    ) {
        val workers = workerCatalogService.listWorkers()
        val claudeConnection = settings.claudeCodeConnection()
        val processingTarget = settings.processingTarget(claudeConnection)
        requireProcessingTarget(user, processingTarget, workers)
        if (claudeConnection != null) {
            requireClaudeForwardingTargetReady(processingTarget, workers, claudeConnection)
        }
    }

    private fun UserProfile.SpeechSettings.SpeechToText.claudeCodeConnection(): AiConnection.ClaudeCode? {
        if (engine != UserProfile.SpeechSettings.SpeechToText.Engine.CLAUDE_CODE) return null
        val connectionId = requireNotNull(claudeCodeConnectionId) {
            "Claude Code speech transcription connection is not configured"
        }
        val connection = aiConfigurationProvider.catalog.connections
            .singleOrNull { it.id == connectionId } as? AiConnection.ClaudeCode
            ?: error("Claude Code speech transcription connection not found: ${connectionId.value}")
        require(connection.enabled) { "Claude Code connection is disabled: ${connection.id.value}" }
        require(connection.voiceTranscriptionEnabled) {
            "Claude Code voice transcription is disabled for connection ${connection.id.value}"
        }
        return connection
    }

    private fun UserProfile.SpeechSettings.SpeechToText.processingTarget(
        claudeConnection: AiConnection.ClaudeCode?,
    ): AiExecutionTarget = when (engine) {
        UserProfile.SpeechSettings.SpeechToText.Engine.OPENAI_API ->
            aiConfigurationProvider.resolveAiRuntime(AiRuntimeAssignment.Purpose.SPEECH_TO_TEXT)
                .connection.executionTarget
        UserProfile.SpeechSettings.SpeechToText.Engine.LOCAL_WHISPER -> localWhisper.executionTarget
        UserProfile.SpeechSettings.SpeechToText.Engine.CLAUDE_CODE ->
            requireNotNull(claudeConnection).executionTarget
    }

    private suspend fun requireProcessingTarget(
        user: User,
        target: AiExecutionTarget,
        workers: List<WorkerCatalogEntry>,
    ) {
        if (target !is AiExecutionTarget.Worker) return
        val workerId = ConversationRuntimeWorkerId(target.workerId)
        workerAccessService.requirePermission(user, workerId, WorkerPermission.USE)
        require(workers.any { it.workerId == workerId }) {
            "Speech transcription Worker not found: ${workerId.value}"
        }
        workerTargetResolver.requireOnline(workerId, ConversationRuntimeCapability.AI_REQUEST_RESPONSE)
    }

    private fun requireDirectClaudeTargetReady(
        worker: WorkerCatalogEntry,
        connection: AiConnection.ClaudeCode,
    ) {
        require(
            worker.environmentProfile.operatingSystem.family in CLAUDE_VOICE_OPERATING_SYSTEMS
        ) {
            "Direct Claude Code microphone transcription is unsupported on " +
                worker.environmentProfile.operatingSystem.family
        }
        requireWorkerExecutable(worker, connection.executablePath, "Claude Code")
    }

    private fun requireClaudeForwardingTargetReady(
        target: AiExecutionTarget,
        workers: List<WorkerCatalogEntry>,
        connection: AiConnection.ClaudeCode,
    ) {
        when (target) {
            AiExecutionTarget.Server -> {
                require(System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("linux")) {
                    "Forwarded audio for Claude Code voice transcription requires a Linux execution target"
                }
                requireLocalExecutable(connection.executablePath, "Claude Code")
                CLAUDE_FORWARDING_EXECUTABLES.forEach { executable ->
                    requireLocalExecutable(executable, executable)
                }
            }
            is AiExecutionTarget.Worker -> {
                val worker = workers.singleOrNull { it.workerId.value == target.workerId }
                    ?: error("Claude Code speech transcription Worker not found: ${target.workerId}")
                require(worker.environmentProfile.operatingSystem.family == WorkerOperatingSystem.Family.LINUX) {
                    "Forwarded audio for Claude Code voice transcription requires a Linux execution target"
                }
                requireWorkerExecutable(worker, connection.executablePath, "Claude Code")
                CLAUDE_FORWARDING_EXECUTABLES.forEach { executable ->
                    requireWorkerExecutable(worker, executable, executable)
                }
            }
        }
    }

    private fun requireWorkerExecutable(
        worker: WorkerCatalogEntry,
        executablePath: String,
        displayName: String,
    ) {
        if (executablePath.hasPathSeparator()) return
        require(executablePath in worker.environmentProfile.availableExecutables) {
            "$displayName executable is unavailable on Worker ${worker.workerId.value}: $executablePath"
        }
    }

    private fun requireLocalExecutable(executablePath: String, displayName: String) {
        require(isLocalExecutableAvailable(executablePath)) {
            "$displayName executable is unavailable on Server: $executablePath"
        }
    }

    private fun isLocalExecutableAvailable(executablePath: String): Boolean {
        val direct = File(executablePath)
        if (direct.isAbsolute || executablePath.hasPathSeparator()) {
            return direct.isFile && direct.canExecute()
        }
        return System.getenv("PATH")
            .orEmpty()
            .split(File.pathSeparatorChar)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { directory -> File(directory, executablePath) }
            .any { it.isFile && it.canExecute() }
    }

    private fun String.hasPathSeparator(): Boolean = '/' in this || '\\' in this

    private suspend fun removeOwned(
        owner: SpeechCaptureSessionOwner,
        sessionId: String,
    ): ActiveSpeechCapture = mutex.withLock {
        val key = SpeechCaptureSessionKey(owner, sessionId)
        val session = sessions[key]
            ?: error("Speech capture session not found: $sessionId")
        check(sessions.remove(key, session)) { "Speech capture session changed concurrently" }
        session
    }

    private suspend fun cleanupFailedStart(
        key: SpeechCaptureSessionKey,
        session: ActiveSpeechCapture,
    ) = withContext(NonCancellable) {
        val removed = mutex.withLock { sessions.remove(key, session) }
        if (removed) {
            runCatching { cancelWorkerSession(session.workerSessionId, session.route.worker) }
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

    private sealed interface SpeechCaptureRoute {
        val worker: ConversationRuntimeWorkerIdentity
        fun startCommand(sessionId: String): WorkerAudioCaptureRequest.Command

        data class RecordAudio(
            override val worker: ConversationRuntimeWorkerIdentity,
            val inputId: WorkerAudioInput.Id,
        ) : SpeechCaptureRoute {
            override fun startCommand(sessionId: String) =
                WorkerAudioCaptureRequest.Command.StartAudio(sessionId, inputId)
        }

        data class DirectClaudeCode(
            override val worker: ConversationRuntimeWorkerIdentity,
            val connection: AiConnection.ClaudeCode,
            val language: String?,
        ) : SpeechCaptureRoute {
            override fun startCommand(sessionId: String) =
                WorkerAudioCaptureRequest.Command.StartClaudeCodeMicrophone(
                    sessionId = sessionId,
                    connection = connection,
                    language = language,
                )
        }
    }

    private companion object {
        val EARLY_CANCELLATION_TTL = 3.minutes
        val CLAUDE_VOICE_OPERATING_SYSTEMS = setOf(
            WorkerOperatingSystem.Family.LINUX,
            WorkerOperatingSystem.Family.MACOS,
            WorkerOperatingSystem.Family.WINDOWS,
        )
        val CLAUDE_FORWARDING_EXECUTABLES = listOf("pactl", "paplay")
    }
}
