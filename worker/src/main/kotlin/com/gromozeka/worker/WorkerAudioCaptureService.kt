package com.gromozeka.worker

import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.service.WorkerAudioCaptureHandler
import com.gromozeka.domain.service.WorkerAudioCaptureRequest
import com.gromozeka.domain.service.WorkerAudioCaptureResult
import com.gromozeka.infrastructure.ai.claude.ClaudeCodeVoiceTranscriptionService
import jakarta.annotation.PreDestroy
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

@Service
internal class WorkerAudioCaptureService(
    private val handleFactory: WorkerAudioCaptureHandleFactory,
    @param:Qualifier("applicationScope") private val scope: CoroutineScope,
) : WorkerAudioCaptureHandler {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, SessionState>()
    private val cancelledBeforeStart = mutableMapOf<String, Job>()

    override suspend fun handle(request: WorkerAudioCaptureRequest): WorkerAudioCaptureResult =
        when (val command = request.command) {
            is WorkerAudioCaptureRequest.Command.PrepareClaudeCodeMicrophone -> prepare(command)
            is WorkerAudioCaptureRequest.Command.StartAudio -> start(request, command.sessionId)
            is WorkerAudioCaptureRequest.Command.StartClaudeCodeMicrophone -> start(request, command.sessionId)
            is WorkerAudioCaptureRequest.Command.Stop -> stop(command.sessionId)
            is WorkerAudioCaptureRequest.Command.Cancel -> cancel(command.sessionId)
        }

    private suspend fun prepare(
        command: WorkerAudioCaptureRequest.Command.PrepareClaudeCodeMicrophone,
    ): WorkerAudioCaptureResult {
        handleFactory.prepare(command)
        return WorkerAudioCaptureResult(WorkerAudioCaptureResult.Status.PREPARED)
    }

    private suspend fun start(
        request: WorkerAudioCaptureRequest,
        sessionId: String,
    ): WorkerAudioCaptureResult {
        val reservation = reserve(sessionId)
            ?: error("Worker audio capture session was cancelled before start: $sessionId")
        val handle = try {
            handleFactory.open(request)
        } catch (error: Throwable) {
            mutex.withLock { sessions.remove(sessionId, reservation) }
            throw error
        }

        lateinit var active: SessionState.Active
        val timeoutJob = scope.launch(start = CoroutineStart.LAZY) {
            delay(MAX_CAPTURE_DURATION)
            val expired = mutex.withLock {
                if (sessions.remove(sessionId, active)) active.handle else null
            }
            expired?.cancel()
        }
        active = SessionState.Active(handle, timeoutJob)

        val installed = mutex.withLock {
            if (sessions[sessionId] !== reservation || reservation.cancelRequested) {
                sessions.remove(sessionId, reservation)
                false
            } else {
                sessions[sessionId] = active
                true
            }
        }
        if (!installed) {
            timeoutJob.cancel()
            withContext(NonCancellable) { handle.cancel() }
            error("Worker audio capture session was cancelled while starting: $sessionId")
        }

        timeoutJob.start()
        return WorkerAudioCaptureResult(WorkerAudioCaptureResult.Status.STARTED)
    }

    private suspend fun reserve(sessionId: String): SessionState.Starting? = mutex.withLock {
        cancelledBeforeStart.remove(sessionId)?.let { cancellationExpiry ->
            cancellationExpiry.cancel()
            return@withLock null
        }
        check(sessionId !in sessions) { "Worker audio capture session already exists: $sessionId" }
        SessionState.Starting().also { sessions[sessionId] = it }
    }

    private suspend fun stop(sessionId: String): WorkerAudioCaptureResult =
        removeActive(sessionId).handle.stop()

    private suspend fun cancel(sessionId: String): WorkerAudioCaptureResult {
        var expiryToStart: Job? = null
        val handle = mutex.withLock {
            when (val state = sessions[sessionId]) {
                is SessionState.Starting -> {
                    state.cancelRequested = true
                    null
                }
                is SessionState.Active -> {
                    check(sessions.remove(sessionId, state)) {
                        "Worker audio capture session changed concurrently"
                    }
                    state.timeoutJob.cancel()
                    state.handle
                }
                null -> {
                    if (sessionId !in cancelledBeforeStart) {
                        lateinit var expiry: Job
                        expiry = scope.launch(start = CoroutineStart.LAZY) {
                            delay(EARLY_CANCELLATION_TTL)
                            mutex.withLock { cancelledBeforeStart.remove(sessionId, expiry) }
                        }
                        cancelledBeforeStart[sessionId] = expiry
                        expiryToStart = expiry
                    }
                    null
                }
            }
        }
        expiryToStart?.start()
        handle?.cancel()
        return WorkerAudioCaptureResult(WorkerAudioCaptureResult.Status.CANCELLED)
    }

    private suspend fun removeActive(sessionId: String): SessionState.Active = mutex.withLock {
        when (val state = sessions[sessionId]) {
            is SessionState.Active -> {
                check(sessions.remove(sessionId, state)) {
                    "Worker audio capture session changed concurrently"
                }
                state
            }
            is SessionState.Starting -> error("Worker audio capture session is still starting: $sessionId")
            null -> error("Worker audio capture session not found: $sessionId")
        }
    }.also { it.timeoutJob.cancel() }

    @PreDestroy
    fun shutdown() = runBlocking {
        val active = mutex.withLock {
            cancelledBeforeStart.values.forEach(Job::cancel)
            cancelledBeforeStart.clear()
            sessions.values.filterIsInstance<SessionState.Active>().also { sessions.clear() }
        }
        withContext(NonCancellable) {
            active.forEach { session ->
                session.timeoutJob.cancel()
                session.handle.cancel()
            }
        }
    }

    private sealed interface SessionState {
        data class Starting(
            var cancelRequested: Boolean = false,
        ) : SessionState

        data class Active(
            val handle: WorkerAudioCaptureHandle,
            val timeoutJob: Job,
        ) : SessionState
    }

    private companion object {
        val MAX_CAPTURE_DURATION = 3.minutes
        val EARLY_CANCELLATION_TTL = 3.minutes
    }
}

internal interface WorkerAudioCaptureHandleFactory {
    suspend fun prepare(command: WorkerAudioCaptureRequest.Command.PrepareClaudeCodeMicrophone)
    suspend fun open(request: WorkerAudioCaptureRequest): WorkerAudioCaptureHandle
}

internal interface WorkerAudioCaptureHandle {
    suspend fun stop(): WorkerAudioCaptureResult
    suspend fun cancel()
}

@Service
internal class JvmWorkerAudioCaptureHandleFactory(
    private val claudeCodeVoiceTranscriptionService: ClaudeCodeVoiceTranscriptionService,
    @param:Qualifier("applicationScope") private val scope: CoroutineScope,
) : WorkerAudioCaptureHandleFactory {
    override suspend fun prepare(
        command: WorkerAudioCaptureRequest.Command.PrepareClaudeCodeMicrophone,
    ) {
        claudeCodeVoiceTranscriptionService.prepareLocalMicrophone(
            command.connection,
            command.language,
        )
    }

    override suspend fun open(request: WorkerAudioCaptureRequest): WorkerAudioCaptureHandle =
        when (val command = request.command) {
            is WorkerAudioCaptureRequest.Command.PrepareClaudeCodeMicrophone ->
                error("Worker audio capture factory cannot open a prepare command")
            is WorkerAudioCaptureRequest.Command.StartAudio -> {
                val session = JvmWorkerAudioHardware.start(scope, command.inputId)
                object : WorkerAudioCaptureHandle {
                    override suspend fun stop(): WorkerAudioCaptureResult {
                        val captured = session.stop()
                        return WorkerAudioCaptureResult(
                            status = WorkerAudioCaptureResult.Status.AUDIO_CAPTURED,
                            audioData = captured.bytes,
                            format = captured.format,
                        )
                    }

                    override suspend fun cancel() = session.cancel()
                }
            }
            is WorkerAudioCaptureRequest.Command.StartClaudeCodeMicrophone -> {
                val target = command.connection.executionTarget as? AiExecutionTarget.Worker
                    ?: error("Direct Claude Code microphone capture requires a Worker-targeted connection")
                require(target.workerId == request.target.workerId.value) {
                    "Claude Code microphone connection targets another Worker"
                }
                val session = claudeCodeVoiceTranscriptionService.startLocalMicrophone(
                    command.connection,
                    command.language,
                )
                object : WorkerAudioCaptureHandle {
                    override suspend fun stop(): WorkerAudioCaptureResult = WorkerAudioCaptureResult(
                        status = WorkerAudioCaptureResult.Status.TRANSCRIBED,
                        transcript = session.stop(),
                    )

                    override suspend fun cancel() = session.cancel()
                }
            }
            is WorkerAudioCaptureRequest.Command.Stop,
            is WorkerAudioCaptureRequest.Command.Cancel,
            -> error("Worker audio capture factory cannot open a ${command::class.simpleName} command")
        }
}
