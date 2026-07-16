package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.CommandProcessRunner
import com.gromozeka.domain.service.CommandProcessSpec
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskOutput
import com.gromozeka.domain.service.CommandTaskService
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventBus
import com.gromozeka.domain.service.RunningCommandProcess
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.filesystem.ExecuteCommandRequest
import com.gromozeka.shared.uuid.uuid7
import jakarta.annotation.PreDestroy
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

@Service
class DefaultCommandTaskService(
    private val processRunner: CommandProcessRunner,
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val runtimeEventBus: ConversationRuntimeEventBus,
) : CommandTaskService {
    private val log = KLoggers.logger(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("command-tasks"))
    private val activeCommands = ConcurrentHashMap<CommandTask.Id, ActiveCommand>()

    override suspend fun start(
        request: ExecuteCommandRequest,
        context: ToolExecutionContext,
    ): CommandTaskOutput {
        validateRequest(request)
        val conversationId = context.requiredConversationId()
        val workingDirectory = resolveWorkingDirectory(context.requiredProjectPath(), request.working_directory)
        val taskId = CommandTask.Id(uuid7())
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
            command = request.command,
            workingDirectory = workingDirectory,
            status = CommandTask.Status.WORKING,
            processId = process.processId,
            processStartedAt = process.processStartedAt,
            outputFile = process.outputFile,
            outputBytes = 0,
            statusMessage = "Command is running",
            createdAt = now,
            updatedAt = now,
        )
        val activeCommand = ActiveCommand(task, process, request.timeout_seconds)
        try {
            check(runtimeCoordinator.upsertCommandTask(task)) {
                "Command task persistence rejected: ${task.id.value}"
            }
            activeCommands[task.id] = activeCommand
            publishSnapshot(conversationId)
            scope.launch {
                monitor(activeCommand)
            }
        } catch (error: Throwable) {
            activeCommands.remove(task.id, activeCommand)
            try {
                process.terminateTree()
            } catch (terminationError: Throwable) {
                error.addSuppressed(terminationError)
            }
            throw error
        }

        try {
            awaitInitialResult(activeCommand, request.yield_time_ms, context)
        } catch (error: CancellationException) {
            cancel(conversationId, task.id)
            throw error
        }
        return output(currentTask(conversationId, task.id) ?: task, 0)
    }

    override suspend fun get(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
        afterByte: Long,
        waitMillis: Long,
    ): CommandTaskOutput? {
        require(afterByte >= 0) { "after_byte must be non-negative" }
        require(waitMillis in 0..MAX_WAIT_MILLIS) { "wait_ms must be between 0 and $MAX_WAIT_MILLIS" }
        val initial = runtimeCoordinator.findCommandTask(conversationId, taskId) ?: return null
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
                delay(POLL_INTERVAL_MILLIS)
            }
        }
        return output(currentTask(conversationId, taskId) ?: initial, afterByte)
    }

    override suspend fun cancel(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
    ): Boolean {
        val stored = runtimeCoordinator.findCommandTask(conversationId, taskId) ?: return false
        if (stored.isTerminal) {
            return false
        }
        val activeCommand = activeCommands[taskId]
        if (activeCommand != null) {
            return activeCommand.mutex.withLock {
                if (activeCommand.task.isTerminal) {
                    return@withLock false
                }
                if (!activeCommand.process.isAlive()) {
                    val exitCode = activeCommand.process.exitCode()
                    complete(
                        activeCommand = activeCommand,
                        status = if (exitCode == 0) CommandTask.Status.COMPLETED else CommandTask.Status.FAILED,
                        exitCode = exitCode,
                        statusMessage = "Command exited with code $exitCode before cancellation",
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
                true
            }
        }

        val reconnected = stored.processId?.let { processId ->
            stored.processStartedAt?.let { startedAt ->
                processRunner.reconnect(processId, startedAt, stored.outputFile)
            }
        }
        reconnected?.terminateTree()
        val now = Clock.System.now()
        check(
            runtimeCoordinator.upsertCommandTask(
                stored.copy(
                    status = CommandTask.Status.CANCELLED,
                    outputBytes = File(stored.outputFile).length(),
                    statusMessage = if (reconnected == null) {
                        "Command process was no longer running"
                    } else {
                        "Command was cancelled"
                    },
                    updatedAt = now,
                    completedAt = now,
                )
            )
        ) { "Command task cancellation update rejected: ${stored.id.value}" }
        publishSnapshot(conversationId)
        return true
    }

    override suspend fun cancelAll(conversationId: Conversation.Id): Int {
        val tasks = runtimeCoordinator.snapshot(conversationId).commandTasks
            .filter { it.status == CommandTask.Status.WORKING }
        return tasks.count { cancel(conversationId, it.id) }
    }

    private suspend fun monitor(activeCommand: ActiveCommand) {
        try {
            val timeoutDeadline = activeCommand.timeoutSeconds?.let { timeout ->
                System.nanoTime() + timeout.seconds.inWholeNanoseconds
            }
            var lastPublishedBytes = 0L
            var lastPublishedAt = System.nanoTime()
            while (true) {
                if (activeCommand.process.waitFor(POLL_INTERVAL_MILLIS)) {
                    activeCommand.mutex.withLock {
                        if (!activeCommand.task.isTerminal) {
                            val exitCode = activeCommand.process.exitCode()
                            complete(
                                activeCommand = activeCommand,
                                status = if (exitCode == 0) CommandTask.Status.COMPLETED else CommandTask.Status.FAILED,
                                exitCode = exitCode,
                                statusMessage = if (exitCode == 0) {
                                    "Command completed"
                                } else {
                                    "Command exited with code $exitCode"
                                },
                            )
                        }
                    }
                    return
                }
                if (timeoutDeadline != null && System.nanoTime() >= timeoutDeadline) {
                    activeCommand.mutex.withLock {
                        if (!activeCommand.task.isTerminal) {
                            activeCommand.process.terminateTree()
                            complete(
                                activeCommand = activeCommand,
                                status = CommandTask.Status.FAILED,
                                exitCode = null,
                                statusMessage = "Command timed out after ${activeCommand.timeoutSeconds} seconds",
                            )
                        }
                    }
                    return
                }
                val outputBytes = File(activeCommand.task.outputFile).length()
                val now = System.nanoTime()
                if (outputBytes != lastPublishedBytes && now - lastPublishedAt >= PROGRESS_PUBLISH_INTERVAL_NANOS) {
                    activeCommand.mutex.withLock {
                        if (!activeCommand.task.isTerminal) {
                            activeCommand.task = activeCommand.task.copy(
                                outputBytes = outputBytes,
                                updatedAt = Clock.System.now(),
                            )
                            check(runtimeCoordinator.upsertCommandTask(activeCommand.task)) {
                                "Command task progress update rejected: ${activeCommand.task.id.value}"
                            }
                            publishSnapshot(activeCommand.task.conversationId)
                        }
                    }
                    lastPublishedBytes = outputBytes
                    lastPublishedAt = now
                }
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
        check(runtimeCoordinator.upsertCommandTask(activeCommand.task)) {
            "Command task update rejected: ${activeCommand.task.id.value}"
        }
        publishSnapshot(activeCommand.task.conversationId)
        activeCommands.remove(activeCommand.task.id, activeCommand)
        activeCommand.completed.complete(Unit)
    }

    private suspend fun awaitInitialResult(
        activeCommand: ActiveCommand,
        yieldMillis: Long,
        context: ToolExecutionContext,
    ) {
        val deadline = System.nanoTime() + yieldMillis * 1_000_000
        while (!activeCommand.completed.isCompleted && System.nanoTime() < deadline) {
            context.cancellationSignal.throwIfCancellationRequested()
            delay(POLL_INTERVAL_MILLIS)
        }
        context.cancellationSignal.throwIfCancellationRequested()
    }

    private fun output(task: CommandTask, afterByte: Long): CommandTaskOutput {
        val file = File(task.outputFile)
        check(file.isFile) { "Command output artifact is missing: ${file.absolutePath}" }
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
        require(request.yield_time_ms in 0..MAX_WAIT_MILLIS) {
            "yield_time_ms must be between 0 and $MAX_WAIT_MILLIS"
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

    private fun ToolExecutionContext.requiredProjectPath(): String =
        getString("projectPath")?.takeIf { it.isNotBlank() }
            ?: error("projectPath is required in tool context")

    private fun resolveWorkingDirectory(projectPath: String, requested: String?): String {
        val projectDirectory = File(projectPath).absoluteFile.normalize()
        val directory = requested
            ?.let(::File)
            ?.let { if (it.isAbsolute) it else File(projectDirectory, requested) }
            ?: projectDirectory
        require(directory.isDirectory) {
            "Command working directory does not exist or is not a directory: ${directory.absolutePath}"
        }
        return directory.absoluteFile.normalize().path
    }

    @PreDestroy
    fun close() {
        runBlocking {
            activeCommands.values.toList().forEach { activeCommand ->
                runCatching { cancel(activeCommand.task.conversationId, activeCommand.task.id) }
            }
        }
        scope.cancel()
    }

    private class ActiveCommand(
        var task: CommandTask,
        val process: RunningCommandProcess,
        val timeoutSeconds: Long?,
    ) {
        val mutex = Mutex()
        val completed = CompletableDeferred<Unit>()
    }

    private companion object {
        const val MAX_WAIT_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 100L
        const val MAX_OUTPUT_CHUNK_BYTES = 64 * 1024
        const val PROGRESS_PUBLISH_INTERVAL_NANOS = 1_000_000_000L
    }
}
