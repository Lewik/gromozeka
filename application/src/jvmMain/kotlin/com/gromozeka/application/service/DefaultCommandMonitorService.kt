package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorEvent
import com.gromozeka.domain.service.CommandMonitorOutput
import com.gromozeka.domain.service.CommandMonitorService
import com.gromozeka.domain.service.CommandMonitorSpec
import com.gromozeka.domain.service.CommandRuntimeStateService
import com.gromozeka.domain.service.MAX_COMMAND_MONITOR_WAIT_MILLIS
import com.gromozeka.domain.service.CommandProcessRecovery
import com.gromozeka.domain.service.CommandProcessRecoverySpec
import com.gromozeka.domain.service.CommandProcessRunner
import com.gromozeka.domain.service.CommandProcessSpec
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.RunningCommandProcess
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredWorkspaceMountId
import com.gromozeka.shared.uuid.uuid7
import jakarta.annotation.PreDestroy
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "true",
)
class DefaultCommandMonitorService(
    private val processRunner: CommandProcessRunner,
    private val runtimeState: CommandRuntimeStateService,
    private val runtimeWorkerDescriptor: ObjectProvider<ConversationRuntimeWorkerDescriptor>,
) : CommandMonitorService {
    private val log = KLoggers.logger(this)
    private val workerId get() = runtimeWorkerDescriptor.getObject().id
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO + CoroutineName("command-monitors"))
    private val activeMonitors = ConcurrentHashMap<CommandMonitor.Id, ActiveMonitor>()
    private val lifecycleMutex = Mutex()
    private val monitorMutexes = Array(MONITOR_MUTEX_STRIPES) { Mutex() }

    @EventListener(ApplicationReadyEvent::class)
    fun recoverOnStartup() {
        scope.launch { recoverPersistedMonitorsWhenAvailable() }
    }

    override suspend fun start(
        spec: CommandMonitorSpec,
        context: ToolExecutionContext,
    ): CommandMonitor {
        val conversationId = context.requiredConversationId()
        val sourceTask = runtimeState.findCommandTask(conversationId, spec.commandTaskId)
            ?: error("Command task not found: ${spec.commandTaskId.value}")
        check(sourceTask.workerId == workerId) {
            "Command task ${sourceTask.id.value} belongs to worker ${sourceTask.workerId.value}, not ${workerId.value}"
        }
        check(sourceTask.workspaceMountId == context.requiredWorkspaceMountId()) {
            "Command monitor must execute on the source command workspace mount"
        }
        val sourceOutputFile = File(sourceTask.outputFile)
        check(sourceOutputFile.isFile) {
            "Command output is no longer available for monitoring: ${sourceOutputFile.absolutePath}"
        }
        if (sourceTask.isTerminal && spec.startFrom == CommandMonitor.StartFrom.NOW) {
            error("Cannot monitor future output of terminal command ${sourceTask.id.value}; use BEGINNING")
        }

        val monitorId = CommandMonitor.Id(uuid7())
        val activeMonitor = lifecycleMutex.withLock {
            val process = processRunner.start(
                CommandProcessSpec(
                    executionId = "monitor-${monitorId.value}",
                    command = spec.filterCommand,
                    workingDirectory = sourceTask.workingDirectory,
                    captureStandardErrorSeparately = true,
                )
            )
            check(process.acceptsInput) { "New command monitor process must accept streaming input" }
            val errorFile = process.errorFile
                ?: error("Command monitor process must capture standard error separately")
            val now = Clock.System.now()
            val sourceCursor = when (spec.startFrom) {
                CommandMonitor.StartFrom.NOW -> sourceOutputFile.length()
                CommandMonitor.StartFrom.BEGINNING -> 0L
            }
            val monitor = CommandMonitor(
                id = monitorId,
                conversationId = conversationId,
                commandTaskId = sourceTask.id,
                workerId = workerId,
                workspaceMountId = sourceTask.workspaceMountId,
                agentDefinitionId = context.agentDefinitionIdOrNull(),
                filterCommand = spec.filterCommand,
                mode = spec.mode,
                startFrom = spec.startFrom,
                status = CommandMonitor.Status.WORKING,
                sourceOutputCursor = sourceCursor,
                processId = process.processId,
                processStartedAt = process.processStartedAt,
                processTreeId = process.processTreeId,
                outputFile = process.outputFile,
                errorFile = errorFile,
                outputBytes = 0,
                eventOutputCursor = 0,
                createdAt = now,
                updatedAt = now,
                terminalNotificationRequestedAt = now.takeIf { context.agentDefinitionIdOrNull() != null },
            )
            val active = ActiveMonitor(
                monitor = monitor,
                process = process,
                sourceTask = sourceTask,
                mutex = monitorMutex(monitor.id),
            )
            try {
                active.mutex.withLock {
                    active.monitor = synchronize(active.monitor).monitor
                    activeMonitors[monitor.id] = active
                    publishSnapshot(conversationId)
                    scope.launch { monitor(active) }
                }
            } catch (error: Throwable) {
                activeMonitors.remove(monitor.id, active)
                runCatching(process::terminateTree)
                runCatching { processRunner.deleteOutputArtifacts(process.outputFile) }
                    .onFailure(error::addSuppressed)
                throw error
            }
            active
        }
        return activeMonitor.monitor
    }

    override suspend fun get(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
        afterByte: Long,
        waitMillis: Long,
    ): CommandMonitorOutput? {
        require(afterByte >= 0) { "after_byte must be non-negative" }
        require(waitMillis in 0..MAX_COMMAND_MONITOR_WAIT_MILLIS) {
            "wait_ms must be between 0 and $MAX_COMMAND_MONITOR_WAIT_MILLIS"
        }
        val initial = currentMonitor(conversationId, monitorId) ?: return null
        check(initial.workerId == workerId) {
            "Command monitor ${initial.id.value} belongs to worker ${initial.workerId.value}, not ${workerId.value}"
        }
        val currentOutputSize = initial.currentOutputSize()
        require(afterByte <= currentOutputSize) {
            "after_byte $afterByte exceeds current monitor output size $currentOutputSize"
        }
        if (!initial.isTerminal && waitMillis > 0) {
            val deadline = System.nanoTime() + waitMillis * 1_000_000
            while (System.nanoTime() < deadline) {
                val current = currentMonitor(conversationId, monitorId) ?: return null
                if (current.isTerminal || current.currentOutputSize() > afterByte) {
                    return output(current, afterByte)
                }
                delay(MONITOR_POLL_INTERVAL_MILLIS)
            }
        }
        return output(currentMonitor(conversationId, monitorId) ?: initial, afterByte)
    }

    override suspend fun cancel(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
    ): Boolean = monitorMutex(monitorId).withLock {
        val stored = runtimeState.findCommandMonitor(conversationId, monitorId) ?: return@withLock false
        if (stored.workerId != workerId || stored.isTerminal) return@withLock false
        val active = activeMonitors[monitorId]
        if (active != null) {
            if (active.monitor.isTerminal) return@withLock false
            runCatching(active.process::terminateTree)
            finalize(
                active = active,
                status = CommandMonitor.Status.CANCELLED,
                exitCode = null,
                statusMessage = "Command monitor was cancelled",
            )
            return@withLock true
        }
        finalizeRecoveredMonitor(
            monitor = stored,
            statusMessage = "Command monitor was cancelled after its worker state was lost",
            terminateRunningProcess = true,
            cancelled = true,
        )
        true
    }

    internal suspend fun recoverPersistedMonitors() = lifecycleMutex.withLock {
        runtimeState.findCommandMonitors()
            .asSequence()
            .filter { it.workerId == workerId && !it.isTerminal }
            .sortedBy { it.createdAt }
            .forEach { monitor ->
                monitorMutex(monitor.id).withLock {
                    finalizeRecoveredMonitor(
                        monitor = monitor,
                        statusMessage =
                            "Command monitor stopped because streaming input cannot be resumed after worker restart",
                        terminateRunningProcess = true,
                        cancelled = false,
                    )
                }
            }
    }

    private suspend fun recoverPersistedMonitorsWhenAvailable() {
        var consecutiveFailures = 0L
        while (currentCoroutineContext().isActive) {
            try {
                recoverPersistedMonitors()
                if (consecutiveFailures > 0) {
                    log.info {
                        "Command monitor recovery resumed after " +
                            "$consecutiveFailures unavailable control-plane checks"
                    }
                }
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                consecutiveFailures += 1
                if (
                    consecutiveFailures == 1L ||
                    consecutiveFailures % CONTROL_PLANE_WAIT_LOG_INTERVAL_ATTEMPTS == 0L
                ) {
                    log.warn {
                        "Command monitor recovery is waiting for the control plane " +
                            "(attempt $consecutiveFailures): ${error::class.simpleName}: ${error.message}"
                    }
                }
                delay(CONTROL_PLANE_RETRY_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun monitor(active: ActiveMonitor) {
        var nextStatePollAt = 0L
        var lastProgressSyncAt = System.nanoTime()
        try {
            while (currentCoroutineContext().isActive) {
                currentCoroutineContext().ensureActive()
                val nowNanos = System.nanoTime()
                var stopMonitoring = false
                active.mutex.withLock {
                    if (active.monitor.isTerminal) {
                        stopMonitoring = true
                        return@withLock
                    }
                    if (nowNanos >= nextStatePollAt) {
                        nextStatePollAt = nowNanos + STATE_POLL_INTERVAL_NANOS
                        refreshControlPlaneState(active)
                    }
                    if (active.monitor.cancellationRequestedAt != null) {
                        runCatching(active.process::terminateTree)
                        finalize(
                            active = active,
                            status = CommandMonitor.Status.CANCELLED,
                            exitCode = null,
                            statusMessage = "Command monitor was cancelled",
                        )
                        stopMonitoring = true
                        return@withLock
                    }

                    feedSourceOutput(active)
                    persistScannedEvents(active, flushTrailing = !active.process.isAlive())

                    if (active.monitor.mode == CommandMonitor.Mode.ONCE && active.monitor.eventCount > 0) {
                        runCatching(active.process::terminateTree)
                        finalize(
                            active = active,
                            status = CommandMonitor.Status.COMPLETED,
                            exitCode = 0,
                            statusMessage = "Command monitor matched once",
                        )
                        stopMonitoring = true
                        return@withLock
                    }

                    closeInputAfterSourceCompletion(active)
                    if (!active.process.isAlive()) {
                        persistScannedEvents(active, flushTrailing = true)
                        val exitCode = active.process.exitCode()
                        finalize(
                            active = active,
                            status = if (exitCode == 0) {
                                CommandMonitor.Status.COMPLETED
                            } else {
                                CommandMonitor.Status.FAILED
                            },
                            exitCode = exitCode,
                            statusMessage = when {
                                exitCode == 0 -> "Command monitor completed"
                                active.monitor.mode == CommandMonitor.Mode.ONCE &&
                                    active.monitor.eventCount == 0L ->
                                    "Command monitor filter exited without a match (code $exitCode)"
                                else -> "Command monitor filter exited with code $exitCode"
                            },
                        )
                        stopMonitoring = true
                        return@withLock
                    }

                    if (nowNanos - lastProgressSyncAt >= PROGRESS_SYNC_INTERVAL_NANOS) {
                        lastProgressSyncAt = nowNanos
                        trySynchronizeProgress(active)
                    }
                }
                if (stopMonitoring) return
                delay(MONITOR_POLL_INTERVAL_MILLIS)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.error(error) { "Command monitor failed: ${active.monitor.id.value}" }
            active.mutex.withLock {
                if (!active.monitor.isTerminal) {
                    runCatching(active.process::terminateTree)
                    val failed = active.monitor.copy(
                        status = CommandMonitor.Status.FAILED,
                        statusMessage = error.message ?: "Command monitor failed",
                        updatedAt = Clock.System.now(),
                        completedAt = Clock.System.now(),
                    ).withTerminalOutput()
                    synchronizeUntilAvailable(active, failed, emptyList())
                }
            }
        } finally {
            activeMonitors.remove(active.monitor.id, active)
            active.completed.complete(Unit)
        }
    }

    private suspend fun refreshControlPlaneState(active: ActiveMonitor) {
        try {
            val storedMonitor = runtimeState.findCommandMonitor(
                active.monitor.conversationId,
                active.monitor.id,
            ) ?: error("Command monitor disappeared from runtime state: ${active.monitor.id.value}")
            val sourceTask = runtimeState.findCommandTask(
                active.monitor.conversationId,
                active.monitor.commandTaskId,
            ) ?: error("Source command disappeared from runtime state: ${active.monitor.commandTaskId.value}")
            active.monitor = active.monitor.copy(
                cancellationRequestedAt =
                    active.monitor.cancellationRequestedAt ?: storedMonitor.cancellationRequestedAt,
                terminalNotificationDeliveredAt =
                    active.monitor.terminalNotificationDeliveredAt ?: storedMonitor.terminalNotificationDeliveredAt,
            )
            active.sourceTask = sourceTask
            active.lastControlPlaneWarningAtNanos = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            warnControlPlaneUnavailable(active, error)
        }
    }

    private fun feedSourceOutput(active: ActiveMonitor) {
        val sourceFile = File(active.sourceTask.outputFile)
        if (!sourceFile.isFile) {
            check(active.sourceTask.isTerminal && active.monitor.sourceOutputCursor >= active.sourceTask.outputBytes) {
                "Source command output artifact is missing: ${sourceFile.absolutePath}"
            }
            return
        }
        var cursor = active.monitor.sourceOutputCursor
        var remainingPasses = MAX_SOURCE_CHUNKS_PER_PASS
        RandomAccessFile(sourceFile, "r").use { source ->
            while (cursor < source.length() && remainingPasses > 0 && active.process.isAlive()) {
                val bytesToRead = min(source.length() - cursor, SOURCE_CHUNK_BYTES.toLong()).toInt()
                val bytes = ByteArray(bytesToRead)
                source.seek(cursor)
                source.readFully(bytes)
                try {
                    active.process.writeInput(bytes)
                } catch (error: Throwable) {
                    if (active.process.isAlive()) throw error
                    return
                }
                cursor += bytes.size
                remainingPasses -= 1
            }
        }
        if (cursor != active.monitor.sourceOutputCursor) {
            active.monitor = active.monitor.copy(
                sourceOutputCursor = cursor,
                updatedAt = Clock.System.now(),
            )
        }
    }

    private fun closeInputAfterSourceCompletion(active: ActiveMonitor) {
        if (active.inputClosed || !active.sourceTask.isTerminal) return
        val sourceFile = File(active.sourceTask.outputFile)
        val sourceSize = if (sourceFile.isFile) sourceFile.length() else active.sourceTask.outputBytes
        if (active.monitor.sourceOutputCursor < sourceSize) return
        if (active.process.isAlive()) {
            runCatching(active.process::closeInput)
                .onFailure { error ->
                    if (active.process.isAlive()) throw error
                }
        }
        active.inputClosed = true
    }

    private suspend fun persistScannedEvents(
        active: ActiveMonitor,
        flushTrailing: Boolean,
    ) {
        val scan = scanEvents(
            monitor = active.monitor,
            scanCursor = active.scanCursor,
            flushTrailing = flushTrailing,
            maxEvents = if (active.monitor.mode == CommandMonitor.Mode.ONCE) 1 else MAX_EVENTS_PER_PASS,
        )
        if (scan.events.isEmpty()) {
            active.scanCursor = scan.nextScanCursor
            if (scan.outputBytes != active.monitor.outputBytes) {
                active.monitor = active.monitor.copy(
                    outputBytes = scan.outputBytes,
                    updatedAt = Clock.System.now(),
                )
            }
            return
        }

        val now = Clock.System.now()
        val proposed = active.monitor.copy(
            outputBytes = scan.outputBytes,
            eventOutputCursor = scan.nextEventCursor,
            eventCount = active.monitor.eventCount + scan.events.size,
            lastEventAt = now,
            lastEventPreview = scan.events.last().output.takeLast(EVENT_PREVIEW_CHARS),
            updatedAt = now,
        )
        try {
            active.monitor = synchronize(proposed, scan.events).monitor
            active.scanCursor = scan.nextScanCursor
            active.lastControlPlaneWarningAtNanos = null
            publishSnapshot(active.monitor.conversationId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            active.scanCursor = active.monitor.eventOutputCursor
            warnControlPlaneUnavailable(active, error)
        }
    }

    private suspend fun trySynchronizeProgress(active: ActiveMonitor) {
        try {
            active.monitor = synchronize(active.monitor).monitor
            active.lastControlPlaneWarningAtNanos = null
            publishSnapshot(active.monitor.conversationId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            warnControlPlaneUnavailable(active, error)
        }
    }

    private suspend fun finalize(
        active: ActiveMonitor,
        status: CommandMonitor.Status,
        exitCode: Int?,
        statusMessage: String,
    ) {
        val now = Clock.System.now()
        val terminal = active.monitor.copy(
            status = status,
            outputBytes = File(active.monitor.outputFile).length(),
            exitCode = exitCode,
            statusMessage = statusMessage,
            updatedAt = now,
            completedAt = now,
        ).withTerminalOutput()
        synchronizeUntilAvailable(active, terminal, emptyList())
    }

    private suspend fun synchronizeUntilAvailable(
        active: ActiveMonitor,
        monitor: CommandMonitor,
        events: List<CommandMonitorEvent>,
    ) {
        var candidate = monitor
        while (currentCoroutineContext().isActive) {
            try {
                active.monitor = synchronize(candidate, events).monitor
                active.lastControlPlaneWarningAtNanos = null
                publishSnapshot(active.monitor.conversationId)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                warnControlPlaneUnavailable(active, error)
                delay(CONTROL_PLANE_RETRY_INTERVAL_MILLIS)
                candidate = candidate.copy(updatedAt = Clock.System.now())
            }
        }
    }

    private suspend fun finalizeRecoveredMonitor(
        monitor: CommandMonitor,
        statusMessage: String,
        terminateRunningProcess: Boolean,
        cancelled: Boolean,
    ) {
        val recovery = processRunner.recover(monitor.recoverySpec())
        when (recovery) {
            is CommandProcessRecovery.Running -> if (terminateRunningProcess) recovery.process.terminateTree()
            is CommandProcessRecovery.UnrecoverableRunning ->
                if (terminateRunningProcess) recovery.process.terminateTree()
            is CommandProcessRecovery.Completed,
            is CommandProcessRecovery.Unavailable -> Unit
        }
        val now = Clock.System.now()
        val outputBytes = File(monitor.outputFile).takeIf(File::isFile)?.length() ?: monitor.outputBytes
        val recovered = monitor.copy(
            status = if (cancelled) CommandMonitor.Status.CANCELLED else CommandMonitor.Status.FAILED,
            outputBytes = outputBytes,
            statusMessage = statusMessage,
            updatedAt = now,
            completedAt = now,
        ).withTerminalOutput()
        val events = scanAllRemainingEvents(recovered)
        val synchronized = synchronize(
            recovered.copy(
                eventOutputCursor = events.lastOrNull()?.outputEndByte ?: recovered.eventOutputCursor,
                eventCount = recovered.eventCount + events.size,
                lastEventAt = events.lastOrNull()?.occurredAt ?: recovered.lastEventAt,
                lastEventPreview = events.lastOrNull()?.output?.takeLast(EVENT_PREVIEW_CHARS)
                    ?: recovered.lastEventPreview,
            ),
            events,
        ).monitor
        publishSnapshot(synchronized.conversationId)
    }

    private fun scanAllRemainingEvents(monitor: CommandMonitor): List<CommandMonitorEvent> {
        val events = mutableListOf<CommandMonitorEvent>()
        var eventCursor = monitor.eventOutputCursor
        var scanCursor = eventCursor
        while (true) {
            val scan = scanEvents(
                monitor = monitor.copy(eventOutputCursor = eventCursor),
                scanCursor = scanCursor,
                flushTrailing = true,
                maxEvents = MAX_EVENTS_PER_PASS,
            )
            events += scan.events
            eventCursor = scan.nextEventCursor
            scanCursor = scan.nextScanCursor
            if (scan.events.isEmpty() || eventCursor >= scan.outputBytes) break
        }
        return events
    }

    private suspend fun synchronize(
        monitor: CommandMonitor,
        events: List<CommandMonitorEvent> = emptyList(),
    ) = runtimeState.synchronizeCommandMonitor(monitor, events).also { result ->
        result.evictedMonitors.forEach { evicted ->
            runCatching { processRunner.deleteOutputArtifacts(evicted.outputFile) }
                .onFailure { error ->
                    log.warn(error) { "Failed to delete evicted command monitor output: ${evicted.outputFile}" }
                }
        }
    }

    private fun scanEvents(
        monitor: CommandMonitor,
        scanCursor: Long,
        flushTrailing: Boolean,
        maxEvents: Int,
    ): EventScan {
        val outputFile = File(monitor.outputFile)
        val outputBytes = outputFile.takeIf(File::isFile)?.length() ?: monitor.outputBytes
        if (!outputFile.isFile || monitor.eventOutputCursor >= outputBytes) {
            return EventScan(emptyList(), monitor.eventOutputCursor, monitor.eventOutputCursor, outputBytes)
        }
        var eventCursor = monitor.eventOutputCursor
        var cursor = scanCursor.coerceIn(eventCursor, outputBytes)
        val events = mutableListOf<CommandMonitorEvent>()
        RandomAccessFile(outputFile, "r").use { output ->
            var remainingBytes = MAX_EVENT_SCAN_BYTES_PER_PASS
            val buffer = ByteArray(EVENT_SCAN_BUFFER_BYTES)
            while (cursor < outputBytes && remainingBytes > 0 && events.size < maxEvents) {
                val requested = min(min(outputBytes - cursor, buffer.size.toLong()), remainingBytes.toLong()).toInt()
                output.seek(cursor)
                val read = output.read(buffer, 0, requested)
                if (read <= 0) break
                var index = 0
                while (index < read && events.size < maxEvents) {
                    if (buffer[index] == '\n'.code.toByte()) {
                        val lineEnd = cursor + index + 1
                        events += buildEvent(monitor, eventCursor, lineEnd, output)
                        eventCursor = lineEnd
                    }
                    index += 1
                }
                cursor += index
                remainingBytes -= index
            }
            if (
                flushTrailing &&
                events.size < maxEvents &&
                cursor >= outputBytes &&
                eventCursor < outputBytes
            ) {
                events += buildEvent(monitor, eventCursor, outputBytes, output)
                eventCursor = outputBytes
            }
        }
        return EventScan(
            events = events,
            nextEventCursor = eventCursor,
            nextScanCursor = if (events.size >= maxEvents) eventCursor else cursor,
            outputBytes = outputBytes,
        )
    }

    private fun buildEvent(
        monitor: CommandMonitor,
        lineStart: Long,
        lineEnd: Long,
        output: RandomAccessFile,
    ): CommandMonitorEvent {
        var contentEnd = lineEnd
        if (contentEnd > lineStart) {
            output.seek(contentEnd - 1)
            if (output.read() == '\n'.code) contentEnd -= 1
        }
        if (contentEnd > lineStart) {
            output.seek(contentEnd - 1)
            if (output.read() == '\r'.code) contentEnd -= 1
        }
        val requestedStart = maxOf(lineStart, contentEnd - MAX_EVENT_TEXT_BYTES)
        val bytes = ByteArray((contentEnd - requestedStart).toInt())
        if (bytes.isNotEmpty()) {
            output.seek(requestedStart)
            output.readFully(bytes)
        }
        var safeStart = 0
        while (safeStart < bytes.size && (bytes[safeStart].toInt() and 0xC0) == 0x80) {
            safeStart += 1
        }
        val actualStart = requestedStart + safeStart
        return CommandMonitorEvent(
            id = CommandMonitorEvent.Id("${monitor.id.value}:$lineEnd"),
            conversationId = monitor.conversationId,
            monitorId = monitor.id,
            outputStartByte = actualStart,
            outputEndByte = lineEnd,
            output = String(bytes, safeStart, bytes.size - safeStart, StandardCharsets.UTF_8),
            outputTruncatedBefore = actualStart > lineStart,
            occurredAt = Clock.System.now(),
            deliveryRequested = monitor.agentDefinitionId != null,
        )
    }

    private fun output(monitor: CommandMonitor, afterByte: Long): CommandMonitorOutput {
        val file = File(monitor.outputFile)
        if (!file.isFile) {
            check(monitor.isTerminal) { "Command monitor output artifact is missing: ${file.absolutePath}" }
            return retainedTerminalOutput(monitor, afterByte)
        }
        val size = file.length()
        val start = min(afterByte, size)
        val bytesToRead = min(size - start, MAX_OUTPUT_CHUNK_BYTES.toLong()).toInt()
        val bytes = ByteArray(bytesToRead)
        if (bytesToRead > 0) {
            RandomAccessFile(file, "r").use { output ->
                output.seek(start)
                output.readFully(bytes)
            }
        }
        val safeLength = if (!monitor.isTerminal || start + bytesToRead < size) {
            bytes.completeUtf8PrefixLength()
        } else {
            bytesToRead
        }
        val next = start + safeLength
        return CommandMonitorOutput(
            monitor = monitor.copy(outputBytes = size),
            output = String(bytes, 0, safeLength, StandardCharsets.UTF_8),
            outputStartByte = start,
            nextOutputByte = next,
            hasMoreOutput = next < size,
        )
    }

    private fun retainedTerminalOutput(monitor: CommandMonitor, afterByte: Long): CommandMonitorOutput {
        val tailStart = monitor.terminalOutputStartByte ?: monitor.outputBytes
        val bytes = monitor.terminalOutput.orEmpty().toByteArray(StandardCharsets.UTF_8)
        var offset = (afterByte - tailStart).coerceIn(0, bytes.size.toLong()).toInt()
        while (offset < bytes.size && (bytes[offset].toInt() and 0xC0) == 0x80) {
            offset += 1
        }
        return CommandMonitorOutput(
            monitor = monitor,
            output = String(bytes, offset, bytes.size - offset, StandardCharsets.UTF_8),
            outputStartByte = tailStart + offset,
            nextOutputByte = monitor.outputBytes,
            hasMoreOutput = false,
        )
    }

    private fun CommandMonitor.withTerminalOutput(): CommandMonitor {
        val outputTail = readTail(outputFile, MAX_TERMINAL_OUTPUT_BYTES)
        val errorTail = readTail(errorFile, MAX_TERMINAL_ERROR_BYTES).second
        return copy(
            terminalOutputStartByte = outputTail.first,
            terminalOutput = outputTail.second,
            terminalErrorOutput = errorTail,
        )
    }

    private fun readTail(path: String, maxBytes: Long): Pair<Long, String> {
        val file = File(path)
        if (!file.isFile) return 0L to ""
        val size = file.length()
        val requestedStart = maxOf(0L, size - maxBytes)
        val bytes = ByteArray((size - requestedStart).toInt())
        if (bytes.isNotEmpty()) {
            RandomAccessFile(file, "r").use { output ->
                output.seek(requestedStart)
                output.readFully(bytes)
            }
        }
        var safeStart = 0
        while (safeStart < bytes.size && (bytes[safeStart].toInt() and 0xC0) == 0x80) {
            safeStart += 1
        }
        return (requestedStart + safeStart) to
            String(bytes, safeStart, bytes.size - safeStart, StandardCharsets.UTF_8)
    }

    private suspend fun publishSnapshot(conversationId: Conversation.Id) {
        try {
            runtimeState.publishSnapshot(conversationId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn(error) { "Failed to publish command monitor snapshot: ${error.message}" }
        }
    }

    private suspend fun currentMonitor(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
    ): CommandMonitor? {
        val active = activeMonitors[monitorId]
        if (active != null) return active.mutex.withLock { active.monitor }
        return runtimeState.findCommandMonitor(conversationId, monitorId)
    }

    private fun warnControlPlaneUnavailable(active: ActiveMonitor, error: Throwable) {
        val now = System.nanoTime()
        val lastWarningAt = active.lastControlPlaneWarningAtNanos
        if (lastWarningAt == null || now - lastWarningAt >= CONTROL_PLANE_WARNING_INTERVAL_NANOS) {
            active.lastControlPlaneWarningAtNanos = now
            log.warn(error) {
                "Command monitor continues locally while control plane synchronization is unavailable: " +
                    active.monitor.id.value
            }
        }
    }

    private fun CommandMonitor.currentOutputSize(): Long {
        val file = File(outputFile)
        return when {
            file.isFile -> file.length()
            isTerminal -> outputBytes
            else -> error("Command monitor output artifact is missing: ${file.absolutePath}")
        }
    }

    private fun CommandMonitor.recoverySpec(): CommandProcessRecoverySpec =
        CommandProcessRecoverySpec(
            processId = processId,
            processStartedAt = processStartedAt,
            processTreeId = processTreeId,
            outputFile = outputFile,
        )

    private fun ByteArray.completeUtf8PrefixLength(): Int {
        if (isEmpty()) return 0
        var codePointStart = lastIndex
        while (codePointStart > 0 && (this[codePointStart].toInt() and 0xC0) == 0x80) {
            codePointStart -= 1
        }
        val lead = this[codePointStart].toInt() and 0xFF
        val expectedBytes = when {
            lead and 0x80 == 0 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        return if (size - codePointStart < expectedBytes) codePointStart else size
    }

    private fun ToolExecutionContext.requiredConversationId(): Conversation.Id =
        getString("conversationId")
            ?.takeIf { it.isNotBlank() }
            ?.let(Conversation::Id)
            ?: error("conversationId is required in tool context")

    private fun ToolExecutionContext.agentDefinitionIdOrNull(): AgentDefinition.Id? =
        getString(TOOL_CONTEXT_AGENT_DEFINITION_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let(AgentDefinition::Id)

    private fun monitorMutex(monitorId: CommandMonitor.Id): Mutex {
        val index = (monitorId.value.hashCode().toLong() and Int.MAX_VALUE.toLong()).toInt() %
            monitorMutexes.size
        return monitorMutexes[index]
    }

    @PreDestroy
    fun close() = runBlocking { supervisor.cancelAndJoin() }

    private class ActiveMonitor(
        var monitor: CommandMonitor,
        val process: RunningCommandProcess,
        var sourceTask: CommandTask,
        val mutex: Mutex,
    ) {
        var scanCursor: Long = monitor.eventOutputCursor
        var inputClosed: Boolean = false
        var lastControlPlaneWarningAtNanos: Long? = null
        val completed = CompletableDeferred<Unit>()
    }

    private data class EventScan(
        val events: List<CommandMonitorEvent>,
        val nextEventCursor: Long,
        val nextScanCursor: Long,
        val outputBytes: Long,
    )

    private companion object {
        const val MONITOR_POLL_INTERVAL_MILLIS = 100L
        const val STATE_POLL_INTERVAL_NANOS = 500_000_000L
        const val PROGRESS_SYNC_INTERVAL_NANOS = 1_000_000_000L
        const val CONTROL_PLANE_RETRY_INTERVAL_MILLIS = 1_000L
        const val CONTROL_PLANE_WAIT_LOG_INTERVAL_ATTEMPTS = 60L
        const val CONTROL_PLANE_WARNING_INTERVAL_NANOS = 30_000_000_000L
        const val SOURCE_CHUNK_BYTES = 64 * 1024
        const val MAX_SOURCE_CHUNKS_PER_PASS = 4
        const val EVENT_SCAN_BUFFER_BYTES = 16 * 1024
        const val MAX_EVENT_SCAN_BYTES_PER_PASS = 256 * 1024
        const val MAX_EVENTS_PER_PASS = 64
        const val MAX_EVENT_TEXT_BYTES = 8 * 1024L
        const val EVENT_PREVIEW_CHARS = 512
        const val MAX_OUTPUT_CHUNK_BYTES = 64 * 1024
        const val MAX_TERMINAL_OUTPUT_BYTES = 8 * 1024L
        const val MAX_TERMINAL_ERROR_BYTES = 8 * 1024L
        const val MONITOR_MUTEX_STRIPES = 64
    }
}
