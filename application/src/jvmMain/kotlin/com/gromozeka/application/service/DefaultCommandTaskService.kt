package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.service.CommandProcessRecovery
import com.gromozeka.domain.service.CommandProcessRecoverySpec
import com.gromozeka.domain.service.CommandProcessRunner
import com.gromozeka.domain.service.CommandProcessSpec
import com.gromozeka.domain.service.CommandOutputGarbageCollectionSpec
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandRuntimeStateService
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskOutput
import com.gromozeka.domain.service.CommandTaskService
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.RunningCommandProcess
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.TOOL_CONTEXT_SECRET_ENVIRONMENT
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.requiredWorkspaceMountId
import com.gromozeka.domain.tool.filesystem.ExecuteCommandRequest
import com.gromozeka.domain.tool.filesystem.MAX_COMMAND_INITIAL_YIELD_MILLIS
import com.gromozeka.domain.tool.filesystem.MAX_COMMAND_TASK_WAIT_MILLIS
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
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "true",
)
class DefaultCommandTaskService(
    private val processRunner: CommandProcessRunner,
    private val runtimeState: CommandRuntimeStateService,
    private val runtimeWorkerDescriptor: ObjectProvider<ConversationRuntimeWorkerDescriptor>,
    @Value("\${gromozeka.runtime.command-output.retention-hours:168}")
    private val outputRetentionHours: Long = DEFAULT_OUTPUT_RETENTION_HOURS,
    @Value("\${gromozeka.runtime.command-output.max-total-bytes:1073741824}")
    private val outputMaxTotalBytes: Long = DEFAULT_OUTPUT_MAX_TOTAL_BYTES,
    @Value("\${gromozeka.runtime.command-output.gc-interval-minutes:60}")
    private val outputGcIntervalMinutes: Long = DEFAULT_OUTPUT_GC_INTERVAL_MINUTES,
) : CommandTaskService {
    private val log = KLoggers.logger(this)
    private val workerId get() = runtimeWorkerDescriptor.getObject().id
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO + CoroutineName("command-tasks"))
    private val activeCommands = ConcurrentHashMap<CommandTask.Id, ActiveCommand>()
    private val lifecycleMutex = Mutex()
    private val taskMutexes = Array(TASK_MUTEX_STRIPES) { Mutex() }

    init {
        require(outputRetentionHours >= 0) { "Command output retention hours must be non-negative" }
        require(outputMaxTotalBytes >= 0) { "Command output byte quota must be non-negative" }
        require(outputGcIntervalMinutes > 0) { "Command output GC interval must be positive" }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun recoverOnStartup() {
        scope.launch { recoverPersistedTasksWhenAvailable() }
        scope.launch { runOutputGarbageCollectionLoop() }
    }

    override suspend fun start(
        request: ExecuteCommandRequest,
        context: ToolExecutionContext,
    ): CommandTaskOutput {
        validateRequest(request)
        val conversationId = context.requiredConversationId()
        val workingDirectory = resolveWorkingDirectory(context.requiredWorkspaceRootPath(), request.working_directory)
        val taskId = CommandTask.Id(uuid7())
        val activeCommand = lifecycleMutex.withLock {
            val process = processRunner.start(
                CommandProcessSpec(
                    executionId = taskId.value,
                    command = request.command,
                    workingDirectory = workingDirectory,
                    environment = context.secretEnvironment(),
                )
            )
            val now = Clock.System.now()
            val task = CommandTask(
                id = taskId,
                conversationId = conversationId,
                workerId = workerId,
                workspaceMountId = context.requiredWorkspaceMountId(),
                agentDefinitionId = context.agentDefinitionIdOrNull(),
                command = request.command,
                workingDirectory = workingDirectory,
                status = CommandTask.Status.WORKING,
                processId = process.processId,
                processStartedAt = process.processStartedAt,
                processTreeId = process.processTreeId,
                outputFile = process.outputFile,
                outputBytes = 0,
                timeoutAt = request.timeout_seconds?.let { now + it.seconds },
                statusMessage = "Command is running",
                createdAt = now,
                updatedAt = now,
            )
            val command = ActiveCommand(task, process, taskMutex(task.id))
            try {
                command.mutex.withLock {
                    command.task = persistCommandTask(task)
                    activeCommands[task.id] = command
                    publishSnapshot(conversationId)
                    scope.launch {
                        monitor(command)
                    }
                }
            } catch (error: Throwable) {
                activeCommands.remove(task.id, command)
                try {
                    process.terminateTree()
                } catch (terminationError: Throwable) {
                    error.addSuppressed(terminationError)
                }
                runCatching { processRunner.deleteOutputArtifacts(process.outputFile) }
                    .onFailure(error::addSuppressed)
                throw error
            }
            command
        }

        try {
            awaitInitialResult(activeCommand, request.yield_time_ms, context)
        } catch (error: CancellationException) {
            cancel(conversationId, taskId)
            throw error
        }
        var resultTask = currentTask(conversationId, taskId) ?: activeCommand.task
        if (resultTask.status == CommandTask.Status.WORKING && resultTask.agentDefinitionId != null) {
            resultTask = requestCompletionNotification(activeCommand)
        }
        return output(resultTask, 0)
    }

    private fun ToolExecutionContext.secretEnvironment(): Map<String, String> =
        (get(TOOL_CONTEXT_SECRET_ENVIRONMENT) as? Map<*, *>)
            ?.entries
            ?.associate { (name, value) ->
                require(name is String && value is String) {
                    "Secret command environment must contain string keys and values"
                }
                name to value
            }
            .orEmpty()

    override suspend fun get(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
        afterByte: Long,
        waitMillis: Long,
    ): CommandTaskOutput? {
        require(afterByte >= 0) { "after_byte must be non-negative" }
        require(waitMillis in 0..MAX_COMMAND_TASK_WAIT_MILLIS) {
            "wait_ms must be between 0 and $MAX_COMMAND_TASK_WAIT_MILLIS"
        }
        val initial = runtimeState.findCommandTask(conversationId, taskId) ?: return null
        check(initial.workerId == workerId) {
            "Command task ${taskId.value} belongs to worker ${initial.workerId.value}, not ${workerId.value}"
        }
        val currentOutputSize = initial.currentOutputSize()
        require(afterByte <= currentOutputSize) {
            "after_byte $afterByte exceeds current output size $currentOutputSize"
        }
        if (initial.status == CommandTask.Status.WORKING && waitMillis > 0) {
            val deadline = System.nanoTime() + waitMillis * 1_000_000
            while (System.nanoTime() < deadline) {
                val current = currentTask(conversationId, taskId) ?: return null
                if (current.isTerminal || File(current.outputFile).length() > afterByte) {
                    return output(current, afterByte)
                }
                delay(COMMAND_STATE_POLL_INTERVAL_MILLIS)
            }
        }
        return output(currentTask(conversationId, taskId) ?: initial, afterByte)
    }

    override suspend fun cancel(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
    ): Boolean = taskMutex(taskId).withLock {
        val stored = runtimeState.findCommandTask(conversationId, taskId) ?: return@withLock false
        if (stored.workerId != workerId) return@withLock false
        if (stored.isTerminal) return@withLock false

        val activeCommand = activeCommands[taskId]
        if (activeCommand != null) {
            if (activeCommand.task.isTerminal) return@withLock false
            if (!activeCommand.process.isAlive()) {
                val exitCode = runCatching(activeCommand.process::exitCode).getOrNull()
                complete(
                    activeCommand = activeCommand,
                    status = exitCode?.toTaskStatus() ?: CommandTask.Status.FAILED,
                    exitCode = exitCode,
                    statusMessage = exitCode?.let { "Command exited with code $it before cancellation" }
                        ?: "Command stopped before cancellation without an exit code",
                )
                return@withLock false
            }
            activeCommand.process.terminateTree()
            complete(
                activeCommand = activeCommand,
                status = CommandTask.Status.CANCELLED,
                exitCode = null,
                statusMessage = "Command was cancelled",
            )
            return@withLock true
        }

        when (val recovery = processRunner.recover(stored.recoverySpec())) {
            is CommandProcessRecovery.Running -> {
                recovery.process.terminateTree()
                completeStoredTask(
                    task = stored,
                    status = CommandTask.Status.CANCELLED,
                    exitCode = null,
                    statusMessage = "Command was cancelled after reconnecting to its process",
                )
                true
            }

            is CommandProcessRecovery.Completed -> {
                completeStoredTask(
                    task = stored,
                    status = recovery.exitCode.toTaskStatus(),
                    exitCode = recovery.exitCode,
                    statusMessage = "Command exited with code ${recovery.exitCode} before cancellation",
                )
                false
            }

            is CommandProcessRecovery.UnrecoverableRunning -> {
                recovery.process.terminateTree()
                completeStoredTask(
                    task = stored,
                    status = CommandTask.Status.CANCELLED,
                    exitCode = null,
                    statusMessage = "Command was cancelled because recovery failed: ${recovery.reason}",
                )
                true
            }

            is CommandProcessRecovery.Unavailable -> {
                completeStoredTask(
                    task = stored,
                    status = CommandTask.Status.FAILED,
                    exitCode = null,
                    statusMessage = "Command could not be recovered: ${recovery.reason}",
                )
                false
            }
        }
    }

    override suspend fun cancelAll(conversationId: Conversation.Id): Int {
        val tasks = runtimeState.findCommandTasks()
            .filter {
                it.conversationId == conversationId &&
                    it.workerId == workerId &&
                    it.status == CommandTask.Status.WORKING
            }
        return tasks.count { cancel(conversationId, it.id) }
    }

    internal suspend fun recoverPersistedTasks() = lifecycleMutex.withLock {
        val tasks = runtimeState.findCommandTasks()
            .filter { it.workerId == workerId }
        runCatching {
            garbageCollectOutputArtifacts(tasks)
        }.onFailure { error ->
            log.warn(error) { "Failed to garbage-collect command output artifacts" }
        }
        tasks.asSequence()
            .filter { it.status == CommandTask.Status.WORKING }
            .sortedBy { it.createdAt }
            .forEach { recoverPersistedTask(it) }
    }

    private suspend fun recoverPersistedTasksWhenAvailable() {
        var consecutiveFailures = 0L
        while (currentCoroutineContext().isActive) {
            try {
                recoverPersistedTasks()
                if (consecutiveFailures > 0) {
                    log.info {
                        "Command recovery resumed after $consecutiveFailures unavailable control-plane checks"
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
                        "Command recovery is waiting for the control plane " +
                            "(attempt $consecutiveFailures): ${error::class.simpleName}: ${error.message}"
                    }
                }
                delay(CONTROL_PLANE_RETRY_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun recoverPersistedTask(candidate: CommandTask) {
        taskMutex(candidate.id).withLock {
            val task = runtimeState.findCommandTask(candidate.conversationId, candidate.id)
                ?: return@withLock
            if (task.isTerminal || activeCommands.containsKey(task.id)) return@withLock

            when (val recovery = processRunner.recover(task.recoverySpec())) {
                is CommandProcessRecovery.Running -> {
                    if (task.cancellationRequestedAt != null) {
                        recovery.process.terminateTree()
                        completeStoredTask(
                            task = task,
                            status = CommandTask.Status.CANCELLED,
                            exitCode = null,
                            statusMessage = "Command was cancelled while its worker was restarting",
                        )
                    } else if (task.timeoutAt?.let { Clock.System.now() >= it } == true) {
                        recovery.process.terminateTree()
                        completeStoredTask(
                            task = task,
                            status = CommandTask.Status.FAILED,
                            exitCode = null,
                            statusMessage = "Command timed out while its worker was restarting",
                        )
                    } else {
                        val activeCommand = ActiveCommand(task, recovery.process, taskMutex(task.id))
                        activeCommands[task.id] = activeCommand
                        scope.launch { monitor(activeCommand) }
                        log.info { "Recovered running command task: ${task.id.value}" }
                    }
                }

                is CommandProcessRecovery.Completed -> completeStoredTask(
                    task = task,
                    status = recovery.exitCode.toTaskStatus(),
                    exitCode = recovery.exitCode,
                    statusMessage = if (recovery.exitCode == 0) {
                        "Command completed while its worker was restarting"
                    } else {
                        "Command exited with code ${recovery.exitCode} while its worker was restarting"
                    },
                )

                is CommandProcessRecovery.UnrecoverableRunning -> {
                    recovery.process.terminateTree()
                    completeStoredTask(
                        task = task,
                        status = CommandTask.Status.FAILED,
                        exitCode = null,
                        statusMessage = "Command could not be recovered: ${recovery.reason}",
                    )
                }

                is CommandProcessRecovery.Unavailable -> {
                    completeStoredTask(
                        task = task,
                        status = CommandTask.Status.FAILED,
                        exitCode = null,
                        statusMessage = "Command could not be recovered: ${recovery.reason}",
                    )
                }
            }
        }
    }

    private suspend fun monitor(activeCommand: ActiveCommand) {
        try {
            var lastPublishedBytes = 0L
            var lastPublishedAt = System.nanoTime()
            var nextCancellationPollAt = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val processStopped = if (activeCommand.task.isTerminal) {
                    false
                } else {
                    activeCommand.process.waitFor(COMMAND_STATE_POLL_INTERVAL_MILLIS)
                }
                val now = System.nanoTime()
                val cancellationRequested = if (!activeCommand.task.isTerminal && now >= nextCancellationPollAt) {
                    nextCancellationPollAt = now + CANCELLATION_POLL_INTERVAL_NANOS
                    pollCancellationRequest(activeCommand)
                } else {
                    false
                }
                var stopMonitoring = false
                activeCommand.mutex.withLock {
                    if (activeCommand.task.isTerminal) {
                        stopMonitoring = trySynchronizeCommandTask(activeCommand)
                        return@withLock
                    }
                    when {
                        cancellationRequested -> {
                            activeCommand.process.terminateTree()
                            setTerminalState(
                                activeCommand = activeCommand,
                                status = CommandTask.Status.CANCELLED,
                                exitCode = null,
                                statusMessage = "Command was cancelled",
                            )
                            stopMonitoring = trySynchronizeCommandTask(activeCommand)
                        }

                        processStopped -> {
                            val exitCode = activeCommand.process.exitCode()
                            setTerminalState(
                                activeCommand = activeCommand,
                                status = exitCode.toTaskStatus(),
                                exitCode = exitCode,
                                statusMessage = if (exitCode == 0) {
                                    "Command completed"
                                } else {
                                    "Command exited with code $exitCode"
                                },
                            )
                            stopMonitoring = trySynchronizeCommandTask(activeCommand)
                        }

                        activeCommand.task.timeoutAt?.let { Clock.System.now() >= it } == true -> {
                            activeCommand.process.terminateTree()
                            setTerminalState(
                                activeCommand = activeCommand,
                                status = CommandTask.Status.FAILED,
                                exitCode = null,
                                statusMessage = "Command timed out at ${activeCommand.task.timeoutAt}",
                            )
                            stopMonitoring = trySynchronizeCommandTask(activeCommand)
                        }

                        else -> {
                            val outputBytes = File(activeCommand.task.outputFile).length()
                            val now = System.nanoTime()
                            if (outputBytes != lastPublishedBytes &&
                                now - lastPublishedAt >= PROGRESS_PUBLISH_INTERVAL_NANOS
                            ) {
                                lastPublishedAt = now
                                activeCommand.task = activeCommand.task.copy(
                                    outputBytes = outputBytes,
                                    updatedAt = Clock.System.now(),
                                )
                                if (trySynchronizeCommandTask(activeCommand)) {
                                    lastPublishedBytes = outputBytes
                                }
                            }
                        }
                    }
                }
                if (stopMonitoring) return
                if (activeCommand.task.isTerminal) {
                    delay(CONTROL_PLANE_RETRY_INTERVAL_MILLIS)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.error(error) { "Command task monitor failed: ${activeCommand.task.id.value}" }
            activeCommand.mutex.withLock {
                if (!activeCommand.task.isTerminal) {
                    runCatching { activeCommand.process.terminateTree() }
                    setTerminalState(
                        activeCommand = activeCommand,
                        status = CommandTask.Status.FAILED,
                        exitCode = null,
                        statusMessage = error.message ?: "Command task monitor failed",
                    )
                }
            }
            synchronizeTerminalStateUntilAvailable(activeCommand)
        } finally {
            activeCommands.remove(activeCommand.task.id, activeCommand)
            activeCommand.completed.complete(Unit)
            if (activeCommand.task.isTerminal) {
                runCatching { garbageCollectOutputArtifacts() }
                    .onFailure { error ->
                        log.warn(error) { "Failed to apply command output retention: ${error.message}" }
                    }
            }
        }
    }

    private suspend fun complete(
        activeCommand: ActiveCommand,
        status: CommandTask.Status,
        exitCode: Int?,
        statusMessage: String,
    ) {
        setTerminalState(activeCommand, status, exitCode, statusMessage)
        activeCommand.task = persistCommandTask(activeCommand.task)
        publishSnapshot(activeCommand.task.conversationId)
    }

    private fun setTerminalState(
        activeCommand: ActiveCommand,
        status: CommandTask.Status,
        exitCode: Int?,
        statusMessage: String,
    ) {
        val now = Clock.System.now()
        val terminalOutput = terminalOutput(activeCommand.task.outputFile)
        activeCommand.task = activeCommand.task.copy(
            status = status,
            outputBytes = File(activeCommand.task.outputFile).length(),
            exitCode = exitCode,
            statusMessage = statusMessage,
            updatedAt = now,
            completedAt = now,
            terminalOutputStartByte = terminalOutput.first,
            terminalOutput = terminalOutput.second,
        )
    }

    private suspend fun pollCancellationRequest(activeCommand: ActiveCommand): Boolean =
        try {
            val task = activeCommand.task
            runtimeState.findCommandTask(task.conversationId, task.id)
                ?.cancellationRequestedAt != null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            warnControlPlaneUnavailable(activeCommand, error)
            false
        }

    private suspend fun trySynchronizeCommandTask(activeCommand: ActiveCommand): Boolean =
        try {
            val task = activeCommand.task
            activeCommand.task = persistCommandTask(task)
            publishSnapshot(task.conversationId)
            activeCommand.lastControlPlaneWarningAtNanos = null
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            warnControlPlaneUnavailable(activeCommand, error)
            false
        }

    private suspend fun synchronizeTerminalStateUntilAvailable(activeCommand: ActiveCommand) {
        while (currentCoroutineContext().isActive) {
            val synchronized = activeCommand.mutex.withLock {
                trySynchronizeCommandTask(activeCommand)
            }
            if (synchronized) {
                return
            }
            delay(CONTROL_PLANE_RETRY_INTERVAL_MILLIS)
        }
    }

    private fun warnControlPlaneUnavailable(activeCommand: ActiveCommand, error: Throwable) {
        val now = System.nanoTime()
        val lastWarningAt = activeCommand.lastControlPlaneWarningAtNanos
        if (lastWarningAt == null || now - lastWarningAt >= CONTROL_PLANE_WARNING_INTERVAL_NANOS) {
            activeCommand.lastControlPlaneWarningAtNanos = now
            log.warn(error) {
                "Command continues locally while control plane synchronization is unavailable: " +
                    activeCommand.task.id.value
            }
        }
    }

    private suspend fun completeStoredTask(
        task: CommandTask,
        status: CommandTask.Status,
        exitCode: Int?,
        statusMessage: String,
    ) {
        val now = Clock.System.now()
        val terminalOutput = terminalOutput(task.outputFile)
        val completedTask = task.copy(
                status = status,
                outputBytes = File(task.outputFile).length(),
                exitCode = exitCode,
                statusMessage = statusMessage,
                updatedAt = now,
                completedAt = now,
                terminalOutputStartByte = terminalOutput.first,
                terminalOutput = terminalOutput.second,
        )
        persistCommandTask(completedTask)
        publishSnapshot(task.conversationId)
        runCatching { garbageCollectOutputArtifacts() }
            .onFailure { error ->
                log.warn(error) { "Failed to apply command output retention: ${error.message}" }
            }
    }

    private suspend fun requestCompletionNotification(activeCommand: ActiveCommand): CommandTask =
        activeCommand.mutex.withLock {
            if (activeCommand.task.completionNotificationRequestedAt == null) {
                activeCommand.task = activeCommand.task.copy(
                    completionNotificationRequestedAt = Clock.System.now(),
                    updatedAt = Clock.System.now(),
                )
                trySynchronizeCommandTask(activeCommand)
            }
            activeCommand.task
        }

    private suspend fun persistCommandTask(task: CommandTask): CommandTask {
        val result = runtimeState.upsertCommandTask(task)
        result.evictedTasks.forEach { evictedTask ->
            runCatching { processRunner.deleteOutputArtifacts(evictedTask.outputFile) }
                .onFailure { error ->
                    log.warn(error) { "Failed to delete evicted command output: ${evictedTask.outputFile}" }
                }
        }
        return result.task
    }

    private suspend fun runOutputGarbageCollectionLoop() {
        while (currentCoroutineContext().isActive) {
            delay(outputGcIntervalMinutes.minutes)
            runCatching { garbageCollectOutputArtifacts() }
                .onFailure { error ->
                    log.warn(error) { "Periodic command output garbage collection failed: ${error.message}" }
                }
        }
    }

    private suspend fun garbageCollectOutputArtifacts(
        tasks: List<CommandTask>? = null,
    ) {
        val workerTasks = tasks ?: runtimeState.findCommandTasks()
            .filter { it.workerId == workerId }
        val workerMonitors = runtimeState.findCommandMonitors()
            .filter { it.workerId == workerId }
        val activeMonitors = workerMonitors.filterNot(CommandMonitor::isTerminal)
        val protectedSourceTaskIds = activeMonitors.mapTo(mutableSetOf()) { it.commandTaskId }
        val referenced = workerTasks.mapTo(mutableSetOf()) { it.outputFile }.apply {
            addAll(workerMonitors.map(CommandMonitor::outputFile))
        }
        val protected = workerTasks.asSequence()
            .filter { !it.isTerminal || it.id in protectedSourceTaskIds }
            .mapTo(mutableSetOf()) { it.outputFile }
            .apply {
                addAll(activeMonitors.map(CommandMonitor::outputFile))
            }
        val result = processRunner.garbageCollectOutputArtifacts(
            CommandOutputGarbageCollectionSpec(
                referencedOutputFiles = referenced,
                protectedOutputFiles = protected,
                expireBefore = Clock.System.now() - outputRetentionHours.hours,
                maxTotalBytes = outputMaxTotalBytes,
            )
        )
        if (result.retainedBytes > outputMaxTotalBytes) {
            log.warn {
                "Command output exceeds the configured quota because active outputs are protected: " +
                    "retained=${result.retainedBytes} protected=${result.protectedBytes} " +
                    "quota=$outputMaxTotalBytes"
            }
        }
    }

    private suspend fun awaitInitialResult(
        activeCommand: ActiveCommand,
        yieldMillis: Long,
        context: ToolExecutionContext,
    ) {
        val deadline = System.nanoTime() + yieldMillis * 1_000_000
        while (!activeCommand.completed.isCompleted && System.nanoTime() < deadline) {
            context.cancellationSignal.throwIfCancellationRequested()
            delay(COMMAND_STATE_POLL_INTERVAL_MILLIS)
        }
        context.cancellationSignal.throwIfCancellationRequested()
    }

    private fun output(task: CommandTask, afterByte: Long): CommandTaskOutput {
        val file = File(task.outputFile)
        if (!file.isFile) {
            check(task.isTerminal) { "Command output artifact is missing: ${file.absolutePath}" }
            return retainedTerminalOutput(task, afterByte)
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
        val safeLength = if (task.status == CommandTask.Status.WORKING || start + bytesToRead < size) {
            bytes.completeUtf8PrefixLength()
        } else {
            bytesToRead
        }
        val chunk = if (safeLength == bytes.size) bytes else bytes.copyOf(safeLength)
        val next = start + safeLength
        return CommandTaskOutput(
            task = task.copy(outputBytes = size),
            output = String(chunk, StandardCharsets.UTF_8),
            outputStartByte = start,
            nextOutputByte = next,
            hasMoreOutput = next < size,
        )
    }

    private fun terminalOutput(outputFile: String): Pair<Long, String> {
        val file = File(outputFile)
        if (!file.isFile) {
            return 0L to ""
        }
        val size = file.length()
        val requestedStart = maxOf(0L, size - MAX_TERMINAL_OUTPUT_BYTES)
        val bytes = ByteArray((size - requestedStart).toInt())
        if (bytes.isEmpty()) {
            return 0L to ""
        }
        RandomAccessFile(file, "r").use { output ->
            output.seek(requestedStart)
            output.readFully(bytes)
        }
        var safeStart = 0
        while (safeStart < bytes.size && (bytes[safeStart].toInt() and 0xC0) == 0x80) {
            safeStart += 1
        }
        return (requestedStart + safeStart) to
            String(bytes, safeStart, bytes.size - safeStart, StandardCharsets.UTF_8)
    }

    private fun retainedTerminalOutput(task: CommandTask, afterByte: Long): CommandTaskOutput {
        val tailStart = task.terminalOutputStartByte ?: task.outputBytes
        val bytes = task.terminalOutput.orEmpty().toByteArray(StandardCharsets.UTF_8)
        var offset = (afterByte - tailStart).coerceIn(0, bytes.size.toLong()).toInt()
        while (offset < bytes.size && (bytes[offset].toInt() and 0xC0) == 0x80) {
            offset += 1
        }
        return CommandTaskOutput(
            task = task,
            output = String(bytes, offset, bytes.size - offset, StandardCharsets.UTF_8),
            outputStartByte = tailStart + offset,
            nextOutputByte = task.outputBytes,
            hasMoreOutput = false,
        )
    }

    private fun CommandTask.currentOutputSize(): Long {
        val file = File(outputFile)
        return when {
            file.isFile -> file.length()
            isTerminal -> outputBytes
            else -> error("Command output artifact is missing: ${file.absolutePath}")
        }
    }

    private suspend fun publishSnapshot(conversationId: Conversation.Id) {
        try {
            runtimeState.publishSnapshot(conversationId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn(error) { "Failed to publish command task snapshot: ${error.message}" }
        }
    }

    private suspend fun currentTask(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
    ): CommandTask? {
        val activeCommand = activeCommands[taskId]
        if (activeCommand != null) {
            return activeCommand.mutex.withLock { activeCommand.task }
        }
        return runtimeState.findCommandTask(conversationId, taskId)
    }

    private fun taskMutex(taskId: CommandTask.Id): Mutex {
        val index = (taskId.value.hashCode().toLong() and Int.MAX_VALUE.toLong()).toInt() % taskMutexes.size
        return taskMutexes[index]
    }

    private fun CommandTask.recoverySpec(): CommandProcessRecoverySpec = CommandProcessRecoverySpec(
        processId = processId,
        processStartedAt = processStartedAt,
        processTreeId = processTreeId,
        outputFile = outputFile,
    )

    private fun Int.toTaskStatus(): CommandTask.Status =
        if (this == 0) CommandTask.Status.COMPLETED else CommandTask.Status.FAILED

    private fun ByteArray.completeUtf8PrefixLength(): Int {
        if (isEmpty()) {
            return 0
        }
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

    private fun validateRequest(request: ExecuteCommandRequest) {
        require(request.command.isNotBlank()) { "command must not be blank" }
        require(request.yield_time_ms in 0..MAX_COMMAND_INITIAL_YIELD_MILLIS) {
            "yield_time_ms must be between 0 and $MAX_COMMAND_INITIAL_YIELD_MILLIS"
        }
        request.timeout_seconds?.let { timeoutSeconds ->
            require(timeoutSeconds > 0) { "timeout_seconds must be positive when provided" }
        }
    }

    private fun ToolExecutionContext.requiredConversationId(): Conversation.Id =
        getString("conversationId")
            ?.takeIf { it.isNotBlank() }
            ?.let(Conversation::Id)
            ?: error("conversationId is required in tool context")

    private fun ToolExecutionContext.requiredWorkspaceRootPath(): String =
        getString("workspaceRootPath")?.takeIf { it.isNotBlank() }
            ?: error("workspaceRootPath is required in tool context")

    private fun ToolExecutionContext.agentDefinitionIdOrNull(): AgentDefinition.Id? =
        getString(TOOL_CONTEXT_AGENT_DEFINITION_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let(AgentDefinition::Id)

    private fun resolveWorkingDirectory(workspaceRootPath: String, requested: String?): String {
        val workspaceDirectory = File(workspaceRootPath).absoluteFile.normalize()
        val directory = requested
            ?.let(::File)
            ?.let { if (it.isAbsolute) it else File(workspaceDirectory, requested) }
            ?: workspaceDirectory
        require(directory.isDirectory) {
            "Command working directory does not exist or is not a directory: ${directory.absolutePath}"
        }
        return directory.absoluteFile.normalize().path
    }

    @PreDestroy
    fun close() = runBlocking { supervisor.cancelAndJoin() }

    private class ActiveCommand(
        var task: CommandTask,
        val process: RunningCommandProcess,
        val mutex: Mutex,
    ) {
        val completed = CompletableDeferred<Unit>()
        var lastControlPlaneWarningAtNanos: Long? = null
    }

    private companion object {
        const val COMMAND_STATE_POLL_INTERVAL_MILLIS = 100L
        const val CONTROL_PLANE_RETRY_INTERVAL_MILLIS = 1_000L
        const val CONTROL_PLANE_WAIT_LOG_INTERVAL_ATTEMPTS = 60L
        const val CONTROL_PLANE_WARNING_INTERVAL_NANOS = 30_000_000_000L
        const val CANCELLATION_POLL_INTERVAL_NANOS = 1_000_000_000L
        const val MAX_OUTPUT_CHUNK_BYTES = 64 * 1024
        const val MAX_TERMINAL_OUTPUT_BYTES = 8 * 1024L
        const val PROGRESS_PUBLISH_INTERVAL_NANOS = 1_000_000_000L
        const val TASK_MUTEX_STRIPES = 64
        const val DEFAULT_OUTPUT_RETENTION_HOURS = 168L
        const val DEFAULT_OUTPUT_MAX_TOTAL_BYTES = 1_073_741_824L
        const val DEFAULT_OUTPUT_GC_INTERVAL_MINUTES = 60L
    }
}
