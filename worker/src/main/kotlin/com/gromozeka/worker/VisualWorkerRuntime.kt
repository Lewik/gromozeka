package com.gromozeka.worker

import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskService
import com.gromozeka.domain.service.ControlPlaneUnavailableException
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.tool.*
import com.gromozeka.domain.tool.filesystem.ExecuteCommandRequest
import com.gromozeka.domain.visual.VisualOutputFramer
import com.gromozeka.domain.visual.VisualOutputRecord
import com.gromozeka.remote.protocol.*
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/** A reader of ordinary managed command output, not a second process runner or a monitor filter. */
@Service
class VisualWorkerRuntime(
    private val commands: CommandTaskService,
    private val outbound: WorkerGatewayOutbound,
    private val identity: ConversationRuntimeWorkerIdentity,
    @param:Qualifier("applicationScope") private val scope: CoroutineScope,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val owned = ConcurrentHashMap<String, Owned>()
    private val cancelled = ConcurrentHashMap<String, Long>()
    private val log = klog.KLoggers.logger(this)

    suspend fun execute(request: VisualWorkerCommand): VisualWorkerResult {
        require(request.worker == identity) { "Visual operation targets another Worker session" }
        require(request.visualId.length in 1..128 && request.generation.length in 1..128) { "Invalid visual identity" }
        return when (request.operation) {
            VisualWorkerCommand.Operation.START -> VisualWorkerResult(start(request))
            VisualWorkerCommand.Operation.INPUT -> {
                val entry = owned[request.generation] ?: error("Visual handler is not running on this Worker session")
                require(entry.request.visualId == request.visualId && entry.request.conversationId == request.conversationId) { "Visual input owner does not match" }
                val task = entry.task.await()
                require(task.id == request.taskId && !cancelled.containsKey(request.generation)) { "Visual handler was replaced or closed" }
                val input = requireNotNull(request.input) { "Visual input is missing" }
                commands.sendInput(request.conversationId, task.id, input.encodeToByteArray(throwOnInvalidSequence = true))
                VisualWorkerResult()
            }
            VisualWorkerCommand.Operation.CANCEL -> {
                cancelled[request.generation] = System.nanoTime()
                pruneCancellations()
                val entry = owned[request.generation]
                if (entry != null) {
                    require(entry.request.visualId == request.visualId && entry.request.conversationId == request.conversationId) { "Visual cancellation owner does not match" }
                    entry.job?.cancel()
                    if (entry.task.isCompleted && !entry.task.isCancelled) {
                        runCatching { entry.task.await() }.getOrNull()?.let { commands.cancel(request.conversationId, it.id) }
                    }
                } else request.taskId?.let { id ->
                    val task = commands.get(request.conversationId, id, 0, 0)?.task
                    require(task == null || task.visualId == request.visualId) { "Task is not this visual's handler" }
                    if (task != null) commands.cancel(request.conversationId, id)
                }
                VisualWorkerResult()
            }
        }
    }

    private suspend fun start(request: VisualWorkerCommand): CommandTask {
        pruneCancellations()
        check(!cancelled.containsKey(request.generation)) { "Visual was closed before handler startup" }
        val workspace = requireNotNull(request.workspace) { "Handler startup requires a workspace" }
        val spec = requireNotNull(request.spec) { "Handler startup requires a command" }
        require(workspace.mount.workerId == identity.workerId.value && workspace.mount.id == spec.workspaceMountId) { "Handler workspace owner does not match" }
        val values = request.toolContext
        require(values[TOOL_CONTEXT_CONVERSATION_ID] == request.conversationId.value &&
            values[TOOL_CONTEXT_PROJECT_ID] == workspace.project.id.value &&
            values[TOOL_CONTEXT_WORKSPACE_MOUNT_ID] == workspace.mount.id.value &&
            values[TOOL_CONTEXT_WORKSPACE_ROOT_PATH] == workspace.mount.rootPath &&
            values[TOOL_CONTEXT_USER_ID]?.isNotBlank() == true) { "Handler context does not match its execution target" }
        val entry = Owned(request)
        owned.putIfAbsent(request.generation, entry)?.let { existing ->
            require(existing.request == request) { "Visual generation was reused for another handler" }
            return existing.task.await()
        }
        try {
            val result = commands.start(
                ExecuteCommandRequest(command = spec.command, working_directory = spec.workingDirectory,
                    yield_time_ms = 0, survive_worker_restart = false),
                ToolExecutionContext(values).withValue(TOOL_CONTEXT_VISUAL_ID, request.visualId)
                    .withValue(TOOL_CONTEXT_SECRET_ENVIRONMENT, request.secretEnvironment),
            )
            entry.task.complete(result.task)
            if (cancelled.containsKey(request.generation)) {
                commands.cancel(request.conversationId, result.task.id)
                error("Visual was closed during handler startup")
            }
            entry.job = scope.launch(Dispatchers.IO + CoroutineName("visual-output-${request.visualId}")) {
                pump(entry, result.task)
            }
            return result.task
        } catch (error: Throwable) {
            entry.task.completeExceptionally(error)
            owned.remove(request.generation, entry)
            throw error
        }
    }

    private suspend fun pump(entry: Owned, initial: CommandTask) {
        val request = entry.request
        var cursor = 0L
        val framer = VisualOutputFramer()
        var lastSentAt = 0L
        var task = initial
        try {
            while (currentCoroutineContext().isActive) {
                val output = commands.get(request.conversationId, initial.id, cursor, 1_000)
                    ?: error("Managed handler task disappeared")
                task = output.task
                check(output.outputStartByte == cursor) { "Managed handler output was truncated" }
                val bytes = output.content.bytes()
                cursor = output.nextOutputByte
                val finished = task.isTerminal && !output.hasMoreOutput
                val records = framer.append(bytes, finished)
                val batches = batches(records)
                for ((index, batch) in batches.withIndex()) {
                    val last = finished && index == batches.lastIndex
                    if (!send(request, task, batch, last)) return
                    lastSentAt = System.nanoTime()
                }
                if (batches.isEmpty() && (finished || System.nanoTime() - lastSentAt >= 5_000_000_000L)) {
                    if (!send(request, task, emptyList(), finished)) return
                    lastSentAt = System.nanoTime()
                }
                if (finished) return
                if (bytes.isEmpty()) delay(50)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log.warn(error) { "Visual output reader failed for ${request.visualId}" }
            runCatching { send(request, task.copy(statusMessage = "Visual output reader failed"), emptyList(), true) }
        } finally {
            owned.remove(request.generation, entry)
            withContext(NonCancellable) {
                runCatching { commands.cancel(request.conversationId, initial.id) }
                    .onFailure { log.warn(it) { "Visual handler cleanup failed for ${request.visualId}" } }
            }
        }
    }

    private suspend fun send(request: VisualWorkerCommand, task: CommandTask, records: List<VisualOutputRecord>, finished: Boolean): Boolean {
        // Do not retransmit the shell program or retained terminal transcript in every state update.
        val metadata = task.copy(command = "", workingDirectory = "", terminalOutputStartByte = null, terminalOutputContent = null)
        val bytes = json.encodeToString(VisualWorkerOutput(request.visualId, request.conversationId,
            request.generation, metadata, records, finished)).encodeToByteArray()
        var delayMillis = 250L
        while (currentCoroutineContext().isActive) {
            try {
                val response = outbound.execute(WorkerGatewayOperation.VISUAL_OUTPUT, bytes)
                return json.decodeFromString<VisualWorkerOutputResult>(response.decodeToString()).active
            } catch (error: ControlPlaneUnavailableException) {
                // Output offsets make replay idempotent. Never retry stdin or process creation here.
                delay(delayMillis)
                delayMillis = (delayMillis * 2).coerceAtMost(5_000)
            }
        }
        return false
    }

    private fun batches(records: List<VisualOutputRecord>): List<List<VisualOutputRecord>> = buildList {
        var batch = mutableListOf<VisualOutputRecord>()
        var bytes = 0
        for (record in records) {
            val size = record.json?.encodeToByteArray()?.size ?: 0
            if (batch.size >= 128 || bytes + size > 32_768) { add(batch); batch = mutableListOf(); bytes = 0 }
            batch.add(record); bytes += size
        }
        if (batch.isNotEmpty()) add(batch)
    }

    private fun pruneCancellations() {
        val before = System.nanoTime() - 300_000_000_000L
        cancelled.entries.removeIf { it.value < before }
    }

    @PreDestroy
    fun close() {
        owned.values.forEach { it.job?.cancel() }
        // Managed commands are WORKER_BOUND and are terminated by CommandTaskService's shutdown.
    }

    private class Owned(val request: VisualWorkerCommand) {
        val task = CompletableDeferred<CommandTask>()
        @Volatile var job: Job? = null
    }
}
