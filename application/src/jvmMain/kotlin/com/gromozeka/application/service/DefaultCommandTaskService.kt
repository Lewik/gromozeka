package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.CommandProcessRecovery
import com.gromozeka.domain.service.CommandProcessRecoverySpec
import com.gromozeka.domain.service.CommandProcessRunner
import com.gromozeka.domain.service.CommandProcessSpec
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskOutput
import com.gromozeka.domain.service.CommandTaskService
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventBus
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.RunningCommandProcess
import com.gromozeka.domain.tool.ToolExecutionContext
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "true",
)
class DefaultCommandTaskService(
    private val processRunner: CommandProcessRunner,
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val runtimeEventBus: ConversationRuntimeEventBus,
    private val runtimeWorkerDescriptor: ObjectProvider<ConversationRuntimeWorkerDescriptor>,
) : CommandTaskService {
    private val log = KLoggers.logger(this)
    private val workerId get() = runtimeWorkerDescriptor.getObject().id
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO + CoroutineName("command-tasks"))
    private val activeCommands = ConcurrentHashMap<CommandTask.Id, ActiveCommand>()
    private val lifecycleMutex = Mutex()
    private val taskMutexes = Array(TASK_MUTEX_STRIPES) { Mutex() }

    @EventListener(ApplicationReadyEvent::class)
    fun recoverOnStartup() = runBlocking {
        recoverPersistedTasks()
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
                    taskId = taskId,
                    command = request.command,
                    workingDirectory = workingDirectory,
                )
            )
            val now = Clock.System.now()
            val task = CommandTask(
                id = taskId,
                conversationId = conversationId,
                workerId = workerId,
                workspaceMountId = context.requiredWorkspaceMountId(),
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
                    persistCommandTask(task)
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
        return output(currentTask(conversationId, taskId) ?: activeCommand.task, 0)
    }

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
        val initial = runtimeCoordinator.findCommandTask(conversationId, taskId) ?: return null
        check(initial.workerId == workerId) {
            "Command task ${taskId.value} belongs to worker ${initial.workerId.value}, not ${workerId.value}"
        }
        require(afterByte <= File(initial.outputFile).length()) {
            "after_byte $afterByte exceeds current output size ${File(initial.outputFile).length()}"
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
        val stored = runtimeCoordinator.findCommandTask(conversationId, taskId) ?: return@withLock false
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
        val tasks = runtimeCoordinator.snapshot(conversationId).commandTasks
            .filter { it.workerId == workerId && it.status == CommandTask.Status.WORKING }
        return tasks.count { cancel(conversationId, it.id) }
    }

    internal suspend fun recoverPersistedTasks() = lifecycleMutex.withLock {
        val tasks = runtimeCoordinator.findCommandTasks()
            .filter { it.workerId == workerId }
        runCatching {
            processRunner.garbageCollectOutputArtifacts(tasks.mapTo(mutableSetOf()) { it.outputFile })
        }.onFailure { error ->
            log.warn(error) { "Failed to garbage-collect command output artifacts" }
        }
        tasks.asSequence()
            .filter { it.status == CommandTask.Status.WORKING }
            .sortedBy { it.createdAt }
            .forEach { recoverPersistedTask(it) }
    }

    private suspend fun recoverPersistedTask(candidate: CommandTask) {
        taskMutex(candidate.id).withLock {
            val task = runtimeCoordinator.findCommandTask(candidate.conversationId, candidate.id)
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
                val processStopped = activeCommand.process.waitFor(COMMAND_STATE_POLL_INTERVAL_MILLIS)
                val now = System.nanoTime()
                val cancellationRequested = if (now >= nextCancellationPollAt) {
                    nextCancellationPollAt = now + CANCELLATION_POLL_INTERVAL_NANOS
                    runtimeCoordinator.findCommandTask(
                        activeCommand.task.conversationId,
                        activeCommand.task.id,
                    )?.cancellationRequestedAt != null
                } else {
                    false
                }
                var stopMonitoring = false
                activeCommand.mutex.withLock {
                    if (activeCommand.task.isTerminal) {
                        stopMonitoring = true
                        return@withLock
                    }
                    when {
                        cancellationRequested -> {
                            activeCommand.process.terminateTree()
                            complete(
                                activeCommand = activeCommand,
                                status = CommandTask.Status.CANCELLED,
                                exitCode = null,
                                statusMessage = "Command was cancelled",
                            )
                            stopMonitoring = true
                        }

                        processStopped -> {
                            val exitCode = activeCommand.process.exitCode()
                            complete(
                                activeCommand = activeCommand,
                                status = exitCode.toTaskStatus(),
                                exitCode = exitCode,
                                statusMessage = if (exitCode == 0) {
                                    "Command completed"
                                } else {
                                    "Command exited with code $exitCode"
                                },
                            )
                            stopMonitoring = true
                        }

                        activeCommand.task.timeoutAt?.let { Clock.System.now() >= it } == true -> {
                            activeCommand.process.terminateTree()
                            complete(
                                activeCommand = activeCommand,
                                status = CommandTask.Status.FAILED,
                                exitCode = null,
                                statusMessage = "Command timed out at ${activeCommand.task.timeoutAt}",
                            )
                            stopMonitoring = true
                        }

                        else -> {
                            val outputBytes = File(activeCommand.task.outputFile).length()
                            val now = System.nanoTime()
                            if (outputBytes != lastPublishedBytes &&
                                now - lastPublishedAt >= PROGRESS_PUBLISH_INTERVAL_NANOS
                            ) {
                                lastPublishedBytes = outputBytes
                                lastPublishedAt = now
                                activeCommand.task = activeCommand.task.copy(
                                    outputBytes = outputBytes,
                                    updatedAt = Clock.System.now(),
                                )
                                persistCommandTask(activeCommand.task)
                                publishSnapshot(activeCommand.task.conversationId)
                            }
                        }
                    }
                }
                if (stopMonitoring) return
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.error(error) { "Command task monitor failed: ${activeCommand.task.id.value}" }
            activeCommand.mutex.withLock {
                if (!activeCommand.task.isTerminal) {
                    runCatching { activeCommand.process.terminateTree() }
                    complete(
                        activeCommand = activeCommand,
                        status = CommandTask.Status.FAILED,
                        exitCode = null,
                        statusMessage = error.message ?: "Command task monitor failed",
                    )
                }
            }
        } finally {
            activeCommands.remove(activeCommand.task.id, activeCommand)
            activeCommand.completed.complete(Unit)
        }
    }

    private suspend fun complete(
        activeCommand: ActiveCommand,
        status: CommandTask.Status,
        exitCode: Int?,
        statusMessage: String,
    ) {
        val now = Clock.System.now()
        activeCommand.task = activeCommand.task.copy(
            status = status,
            outputBytes = File(activeCommand.task.outputFile).length(),
            exitCode = exitCode,
            statusMessage = statusMessage,
            updatedAt = now,
            completedAt = now,
        )
        persistCommandTask(activeCommand.task)
        publishSnapshot(activeCommand.task.conversationId)
    }

    private suspend fun completeStoredTask(
        task: CommandTask,
        status: CommandTask.Status,
        exitCode: Int?,
        statusMessage: String,
    ) {
        val now = Clock.System.now()
        persistCommandTask(
            task.copy(
                status = status,
                outputBytes = File(task.outputFile).length(),
                exitCode = exitCode,
                statusMessage = statusMessage,
                updatedAt = now,
                completedAt = now,
            )
        )
        publishSnapshot(task.conversationId)
    }

    private suspend fun persistCommandTask(task: CommandTask) {
        runtimeCoordinator.upsertCommandTask(task).evictedTasks.forEach { evictedTask ->
            runCatching { processRunner.deleteOutputArtifacts(evictedTask.outputFile) }
                .onFailure { error ->
                    log.warn(error) { "Failed to delete evicted command output: ${evictedTask.outputFile}" }
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
            return CommandTaskOutput(
                task = task.copy(outputBytes = 0),
                output = "",
                outputStartByte = 0,
                nextOutputByte = 0,
                hasMoreOutput = false,
            )
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

    private suspend fun publishSnapshot(conversationId: Conversation.Id) {
        try {
            runtimeEventBus.publish(
                ConversationRuntimeEvent.SnapshotUpdated(
                    conversationId = conversationId,
                    snapshot = runtimeCoordinator.snapshot(conversationId),
                )
            )
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
        return runtimeCoordinator.findCommandTask(conversationId, taskId)
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
    }

    private companion object {
        const val COMMAND_STATE_POLL_INTERVAL_MILLIS = 100L
        const val CANCELLATION_POLL_INTERVAL_NANOS = 1_000_000_000L
        const val MAX_OUTPUT_CHUNK_BYTES = 64 * 1024
        const val PROGRESS_PUBLISH_INTERVAL_NANOS = 1_000_000_000L
        const val TASK_MUTEX_STRIPES = 64
    }
}
