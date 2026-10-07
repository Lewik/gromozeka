package com.gromozeka.worker

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.domain.tool.filesystem.ExecuteCommandRequest
import com.gromozeka.domain.visual.VisualHandlerSpec
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Instant

class VisualWorkerRuntimeTest {
    @Test fun `rejects other worker session mount and context before starting a process`() = fixture { f ->
        assertFails { f.runtime.execute(f.request.copy(worker = f.identity.copy(sessionId = ConversationRuntimeWorkerSessionId("other")))) }
        assertFails { f.runtime.execute(f.request.copy(spec = f.request.spec!!.copy(workspaceMountId = WorkspaceMount.Id("other")))) }
        assertFails { f.runtime.execute(f.request.copy(toolContext = f.request.toolContext + (TOOL_CONTEXT_CONVERSATION_ID to "foreign"))) }
        assertEquals(0, f.commands.starts)
    }

    @Test fun `close before startup and unowned stdin never create or address a process`() = fixture { f ->
        f.runtime.execute(f.request.copy(operation = VisualWorkerCommand.Operation.CANCEL))
        assertFails { f.runtime.execute(f.request) }
        assertFails { f.runtime.execute(f.request.copy(operation = VisualWorkerCommand.Operation.INPUT, input = "{}\n", taskId = f.commands.task.id)) }
        assertEquals(0, f.commands.starts)
        assertTrue(f.commands.inputs.isEmpty())
    }

    @Test fun `owned command receives stdin and its final NDJSON is framed before stopping`() = fixture { f ->
        val started = f.runtime.execute(f.request).task!!
        assertEquals(started.id, f.runtime.execute(f.request).task!!.id)
        assertEquals(1, f.commands.starts)
        assertEquals(false, f.commands.request!!.survive_worker_restart)
        assertEquals(f.request.visualId, f.commands.context!!.getString(TOOL_CONTEXT_VISUAL_ID))
        val input = "{\"button-id\":\"save\",\"state\":{\"form\":{},\"data\":{}}}\n"
        f.runtime.execute(f.request.copy(operation = VisualWorkerCommand.Operation.INPUT, taskId = started.id, input = input))
        assertEquals(listOf(input), f.commands.inputs)
        assertFails { f.runtime.execute(f.request.copy(operation = VisualWorkerCommand.Operation.INPUT, visualId = "foreign", taskId = started.id, input = input)) }
        val text = "{\"data\":{\"text\":\"Привет\"}}\n{\"data\":{\"done\":true}}"
        val bytes = text.encodeToByteArray()
        val terminal = started.copy(status = CommandTask.Status.COMPLETED, exitCode = 0, outputBytes = bytes.size.toLong())
        f.commands.output.send(CommandTaskOutput(terminal, BinaryContent.fromBytes(bytes), 0, bytes.size.toLong(), false))
        val request = withTimeout(3_000) { f.outgoing.receive() } as WorkerGatewayMessage.Request
        assertEquals(WorkerGatewayOperation.VISUAL_OUTPUT, request.operation)
        val output = Json.decodeFromString<VisualWorkerOutput>(request.payload.decodeToString())
        assertTrue(output.finished)
        assertEquals(2, output.records.size)
        assertEquals(bytes.size.toLong(), output.records.last().endByte)
        assertEquals("", output.task.command)
        assertEquals("", output.task.workingDirectory)
        assertEquals(f.request.generation, output.generation)
        f.outbound.accept(WorkerGatewayMessage.Response(request.id, WorkerGatewayMessage.Response.Status.SUCCEEDED, "{\"active\":false}".encodeToByteArray()))
        withTimeout(3_000) { f.commands.cancelled.await() }
    }

    @Test fun `close cancels a reader waiting on server acknowledgement and its owned process`() = fixture { f ->
        val started = f.runtime.execute(f.request).task!!
        val text = "{\"data\":{\"count\":1}}\n"
        f.commands.output.send(CommandTaskOutput(started.copy(outputBytes = text.length.toLong()), BinaryContent.fromText(text), 0, text.length.toLong(), false))
        withTimeout(3_000) { f.outgoing.receive() }
        withTimeout(3_000) { f.runtime.execute(f.request.copy(operation = VisualWorkerCommand.Operation.CANCEL, taskId = started.id)) }
        withTimeout(3_000) { f.commands.cancelled.await() }
        assertFails { f.runtime.execute(f.request.copy(operation = VisualWorkerCommand.Operation.INPUT, taskId = started.id, input = "{}\n")) }
    }

    private fun fixture(block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        val f = Fixture()
        f.outbound.attach(f.outgoing)
        try { block(f) } finally {
            f.runtime.close()
            f.scope.cancel()
            withTimeout(3_000) { f.scope.coroutineContext[Job]!!.join() }
            f.outbound.detach(f.outgoing)
            f.outgoing.close()
        }
    }

    private class Fixture {
        val now = Instant.fromEpochMilliseconds(0)
        val identity = ConversationRuntimeWorkerIdentity(ConversationRuntimeWorkerId("worker"), ConversationRuntimeWorkerSessionId("session"))
        val project = Project(Project.Id("project"), "Test", createdAt = now, lastUsedAt = now)
        val workspace = Workspace(Workspace.Id("workspace"), project.id, "Test", Workspace.Kind.FILESYSTEM, now, now)
        val mount = WorkspaceMount(WorkspaceMount.Id("mount"), workspace.id, identity.workerId.value, "/workspace", now, now)
        val conversationId = Conversation.Id("conversation")
        val commands = FakeCommands(CommandTask(CommandTask.Id("task"), conversationId, identity.workerId, mount.id,
            visualId = "visual", command = "handler", workingDirectory = mount.rootPath, status = CommandTask.Status.WORKING,
            processId = 123, processStartedAt = now, outputFile = "/output", outputBytes = 0, createdAt = now, updatedAt = now))
        val outbound = WorkerGatewayOutbound(ConversationRuntimeWorkerProperties(id = identity.workerId.value))
        val outgoing = Channel<WorkerGatewayMessage>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = VisualWorkerRuntime(commands, outbound, identity, scope)
        val request = VisualWorkerCommand(VisualWorkerCommand.Operation.START, "visual", conversationId, "generation", identity,
            workspace = WorkspaceExecutionContext(project, workspace, mount), spec = VisualHandlerSpec(mount.id, "handler"),
            toolContext = mapOf(TOOL_CONTEXT_CONVERSATION_ID to conversationId.value, TOOL_CONTEXT_PROJECT_ID to project.id.value,
                TOOL_CONTEXT_WORKSPACE_MOUNT_ID to mount.id.value, TOOL_CONTEXT_WORKSPACE_ROOT_PATH to mount.rootPath, TOOL_CONTEXT_USER_ID to "user"))
    }

    private class FakeCommands(val task: CommandTask) : CommandTaskService {
        var starts = 0
        var request: ExecuteCommandRequest? = null
        var context: ToolExecutionContext? = null
        val inputs = mutableListOf<String>()
        val output = Channel<CommandTaskOutput>(Channel.UNLIMITED)
        val cancelled = CompletableDeferred<Unit>()
        override suspend fun start(request: ExecuteCommandRequest, context: ToolExecutionContext): CommandTaskOutput {
            starts++; this.request = request; this.context = context
            return CommandTaskOutput(task, BinaryContent.EMPTY, 0, 0, false)
        }
        override suspend fun get(conversationId: Conversation.Id, taskId: CommandTask.Id, afterByte: Long, waitMillis: Long) = output.receive()
        override suspend fun cancel(conversationId: Conversation.Id, taskId: CommandTask.Id): Boolean { cancelled.complete(Unit); return true }
        override suspend fun cancelAll(conversationId: Conversation.Id) = 0
        override suspend fun sendInput(conversationId: Conversation.Id, taskId: CommandTask.Id, bytes: ByteArray, closeInput: Boolean): CommandTaskInputResult {
            inputs.add(bytes.decodeToString())
            return CommandTaskInputResult(taskId, bytes.size, closeInput)
        }
    }
}
