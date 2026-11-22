package com.gromozeka.worker

import com.gromozeka.domain.service.WorkerAudioCaptureHandler
import com.gromozeka.domain.service.WorkerAudioCaptureRequest
import com.gromozeka.domain.service.WorkerAudioCaptureResult
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
            is WorkerAudioCaptureRequest.Command.StartAudio -> start(request, command.sessionId)
            is WorkerAudioCaptureRequest.Command.Stop -> stop(command.sessionId)
            is WorkerAudioCaptureRequest.Command.Cancel -> cancel(command.sessionId)
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

        val active = SessionState.Active(handle)
        val timeoutJob = createTimeoutJob(sessionId, active)
        active.timeoutJob = timeoutJob
        val installed = try {
            mutex.withLock {
                if (sessions[sessionId] !== reservation || reservation.cancelRequested) {
                    sessions.remove(sessionId, reservation)
                    false
                } else {
                    sessions[sessionId] = active
                    true
                }
            }
        } catch (error: Throwable) {
            timeoutJob.cancel()
            withContext(NonCancellable) { runCatching { handle.cancel() } }
            throw error
        }
        if (!installed) {
            timeoutJob.cancel()
            withContext(NonCancellable) { handle.cancel() }
            error("Worker audio capture session was cancelled while starting: $sessionId")
        }

        timeoutJob.start()
        return WorkerAudioCaptureResult(WorkerAudioCaptureResult.Status.STARTED)
    }

    private fun createTimeoutJob(
        sessionId: String,
        active: SessionState.Active,
    ): Job = scope.launch(start = CoroutineStart.LAZY) {
        delay(MAX_CAPTURE_DURATION)
        val expired = mutex.withLock {
            if (sessions.remove(sessionId, active)) active.handle else null
        }
        expired?.cancel()
    }

    private suspend fun reserve(sessionId: String): SessionState.Starting? = mutex.withLock {
        cancelledBeforeStart.remove(sessionId)?.let { cancellationExpiry ->
            cancellationExpiry.cancel()
            return@withLock null
        }
        check(sessionId !in sessions) { "Worker audio capture session already exists: $sessionId" }
        SessionState.Starting().also { sessions[sessionId] = it }
    }

    private suspend fun stop(sessionId: String): WorkerAudioCaptureResult {
        val terminal = claimActive(sessionId)
        return try {
            val result = terminal.active.handle.stop()
            completeTerminal(sessionId, terminal)
            result
        } catch (error: Throwable) {
            val cancelled = withContext(NonCancellable) {
                runCatching { terminal.active.handle.cancel() }.isSuccess
            }
            if (cancelled) completeTerminal(sessionId, terminal)
            else restoreTerminal(sessionId, terminal)
            throw error
        }
    }

    private suspend fun cancel(sessionId: String): WorkerAudioCaptureResult {
        var expiryToStart: Job? = null
        val terminal = mutex.withLock {
            when (val state = sessions[sessionId]) {
                is SessionState.Starting -> {
                    state.cancelRequested = true
                    null
                }
                is SessionState.Active -> claimActive(sessionId, state)
                is SessionState.Terminal -> error(
                    "Worker audio capture terminal operation is already running: $sessionId"
                )
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
        if (terminal != null) {
            try {
                terminal.active.handle.cancel()
                completeTerminal(sessionId, terminal)
            } catch (error: Throwable) {
                restoreTerminal(sessionId, terminal)
                throw error
            }
        }
        return WorkerAudioCaptureResult(WorkerAudioCaptureResult.Status.CANCELLED)
    }

    private suspend fun claimActive(sessionId: String): SessionState.Terminal = mutex.withLock {
        when (val state = sessions[sessionId]) {
            is SessionState.Active -> claimActive(sessionId, state)
            is SessionState.Starting -> error("Worker audio capture session is still starting: $sessionId")
            is SessionState.Terminal -> error(
                "Worker audio capture terminal operation is already running: $sessionId"
            )
            null -> error("Worker audio capture session not found: $sessionId")
        }
    }

    private fun claimActive(
        sessionId: String,
        active: SessionState.Active,
    ): SessionState.Terminal {
        active.timeoutJob?.cancel()
        return SessionState.Terminal(active).also { sessions[sessionId] = it }
    }

    private suspend fun completeTerminal(
        sessionId: String,
        terminal: SessionState.Terminal,
    ) = withContext(NonCancellable) {
        mutex.withLock { sessions.remove(sessionId, terminal) }
    }

    private suspend fun restoreTerminal(
        sessionId: String,
        terminal: SessionState.Terminal,
    ) = withContext(NonCancellable) {
        val timeoutJob = createTimeoutJob(sessionId, terminal.active)
        terminal.active.timeoutJob = timeoutJob
        val restored = mutex.withLock {
            if (sessions[sessionId] === terminal) {
                sessions[sessionId] = terminal.active
                true
            } else {
                false
            }
        }
        if (restored) timeoutJob.start() else timeoutJob.cancel()
    }

    @PreDestroy
    fun shutdown() = runBlocking {
        val active = mutex.withLock {
            cancelledBeforeStart.values.forEach(Job::cancel)
            cancelledBeforeStart.clear()
            sessions.values.mapNotNull { state ->
                when (state) {
                    is SessionState.Active -> state
                    is SessionState.Terminal -> state.active
                    is SessionState.Starting -> null
                }
            }.also { sessions.clear() }
        }
        withContext(NonCancellable) {
            active.forEach { session ->
                session.timeoutJob?.cancel()
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
            var timeoutJob: Job? = null,
        ) : SessionState

        data class Terminal(
            val active: Active,
        ) : SessionState
    }

    private companion object {
        val MAX_CAPTURE_DURATION = 3.minutes
        val EARLY_CANCELLATION_TTL = 3.minutes
    }
}

internal fun interface WorkerAudioCaptureHandleFactory {
    suspend fun open(request: WorkerAudioCaptureRequest): WorkerAudioCaptureHandle
}

internal interface WorkerAudioCaptureHandle {
    suspend fun stop(): WorkerAudioCaptureResult
    suspend fun cancel()
}

@Service
internal class JvmWorkerAudioCaptureHandleFactory(
    @param:Qualifier("applicationScope") private val scope: CoroutineScope,
) : WorkerAudioCaptureHandleFactory {
    override suspend fun open(request: WorkerAudioCaptureRequest): WorkerAudioCaptureHandle =
        when (val command = request.command) {
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
            is WorkerAudioCaptureRequest.Command.Stop,
            is WorkerAudioCaptureRequest.Command.Cancel,
            -> error("Worker audio capture factory cannot open a ${command::class.simpleName} command")
        }
}
