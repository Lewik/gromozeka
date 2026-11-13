package com.gromozeka.server

import com.gromozeka.application.service.DefaultCommandTaskService
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.WorkerRequestExecutionContext
import com.gromozeka.domain.tool.filesystem.ExecuteCommandRequest
import com.gromozeka.infrastructure.ai.tool.LocalCommandProcessRunner
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Real managed shell/Python/SDK against a synthetic MCP peer; no installed application or user credentials. */
class ManagedCommandScriptSdkTest {
    @Test fun `first Python SDK call uses automatic environment only after task and hash registration`() = runBlocking<Unit> {
        val home = Files.createTempDirectory("managed-script-sdk-").toFile()
        val oldHome = System.getProperty("GROMOZEKA_HOME")
        val state = TestCommandState()
        val registeredHash = AtomicReference<String?>()
        val calls = AtomicInteger()
        val unauthorized = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/mcp") { exchange ->
            try {
                val token = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
                val registered = registeredHash.get()
                val authorized = token != null && registered != null && CommandScriptTokens.hash(token) == registered &&
                    state.tasks.values.singleOrNull()?.status == CommandTask.Status.WORKING
                if (!authorized) {
                    unauthorized.incrementAndGet()
                    exchange.sendResponseHeaders(401, -1)
                    return@createContext
                }
                val request = Json.parseToJsonElement(exchange.requestBody.use { it.readBytes().decodeToString() }).jsonObject
                val method = request.getValue("method").jsonPrimitive.content
                if (method == "notifications/initialized") {
                    exchange.sendResponseHeaders(202, -1)
                    return@createContext
                }
                val result = when (method) {
                    "initialize" -> buildJsonObject { put("protocolVersion", "2025-11-25") }
                    "tools/list" -> Json.parseToJsonElement("""{"tools":[{"name":"test_echo","inputSchema":{"type":"object","properties":{},"additionalProperties":false}}]}""")
                    "tools/call" -> {
                        check(request.getValue("params").jsonObject.getValue("name") == JsonPrimitive("test_echo"))
                        check(request.getValue("params").jsonObject.getValue("arguments").jsonObject.isEmpty())
                        calls.incrementAndGet()
                        Json.parseToJsonElement("""{"content":[{"type":"text","text":"ok"}],"structuredContent":{"ok":true},"isError":false}""")
                    }
                    else -> error("Unexpected synthetic MCP method")
                }
                val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", result) }
                    .toString().toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally { exchange.close() }
        }
        var service: DefaultCommandTaskService? = null
        try {
            System.setProperty("GROMOZEKA_HOME", home.absolutePath)
            server.start()
            val scripts = object : CommandScriptBootstrapService {
                override fun create() = CommandScriptBootstrap("http://127.0.0.1:${server.address.port}/mcp")
                override suspend fun register(task: CommandTask, tokenHash: String): CommandTaskUpsertResult {
                    assertEquals(0, calls.get())
                    assertTrue(File(task.outputFile + ".start.pending").isFile)
                    assertEquals("launch-request", task.launchReference?.workerRequestId)
                    val stored = state.upsertCommandTask(task)
                    assertTrue(registeredHash.compareAndSet(null, tokenHash), "One credential registration per command")
                    return stored
                }
            }
            val descriptor = ConversationRuntimeWorkerDescriptor(ConversationRuntimeWorkerId("script-worker"),
                setOf(ConversationRuntimeCapability.TOOL_EXECUTION, ConversationRuntimeCapability.LOCAL_AGENT_TOOL),
                environmentProfile = Mockito.mock(WorkerEnvironmentProfile::class.java))
            val provider = object : ObjectProvider<ConversationRuntimeWorkerDescriptor> { override fun getObject() = descriptor }
            service = DefaultCommandTaskService(LocalCommandProcessRunner(), state, provider, scripts)
            val script = File(home, "invoke.py").apply { writeText("""
                import grz
                with grz.Client.from_env() as client:
                    result = client.call("test_echo")
                    assert result["structuredContent"]["ok"] is True
                print("SDK_OK")
            """.trimIndent()) }
            val windows = System.getProperty("os.name").startsWith("Windows")
            val python = System.getenv("GRZ_TEST_PYTHON") ?: if (windows) "python" else "python3"
            fun quote(value: String) = if (windows) "\"$value\"" else "'" + value.replace("'", "'\\''") + "'"
            val context = ToolExecutionContext(mapOf("conversationId" to "conversation", "projectId" to "project",
                "workspaceId" to "workspace", "workspaceMountId" to "mount", "workspaceRootPath" to home.absolutePath,
                "workerId" to descriptor.id.value, "userId" to "user", "agentDefinitionId" to "agent", "toolCallId" to "launch-call"),
                workerRequest = WorkerRequestExecutionContext("launch-request", Clock.System.now() + 60.seconds))
            val initial = service.start(ExecuteCommandRequest("${quote(python)} ${quote(script.absolutePath)}", yield_time_ms = 10_000,
                timeout_seconds = 20), context)
            val completed = if (initial.task.isTerminal) initial else assertNotNull(service.get(initial.task.conversationId, initial.task.id, 0, 15_000))
            assertEquals(CommandTask.Status.COMPLETED, completed.task.status, completed.output)
            assertEquals(0, completed.task.exitCode, completed.output)
            assertContains(completed.output, "SDK_OK")
            assertEquals(1, calls.get())
            assertEquals(0, unauthorized.get(), "The child must never race ahead of registration")
            assertNotNull(registeredHash.get())
            assertFalse(completed.output.contains(CommandScriptTokens.PREFIX))
            assertFalse(completed.task.command.contains("GRZ_MCP_"))
        } finally {
            service?.close()
            server.stop(0)
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            if (oldHome == null) System.clearProperty("GROMOZEKA_HOME") else System.setProperty("GROMOZEKA_HOME", oldHome)
            assertTrue(home.deleteRecursively())
        }
    }

    private class TestCommandState : CommandRuntimeStateService {
        val tasks = ConcurrentHashMap<CommandTask.Id, CommandTask>()
        override suspend fun upsertCommandTask(task: CommandTask): CommandTaskUpsertResult =
            CommandTaskUpsertResult(requireNotNull(tasks.compute(task.id) { _, old -> task.mergeCoordinatorState(old) }))
        override suspend fun findCommandTasks(): List<CommandTask> = tasks.values.toList()
        override suspend fun findCommandTask(conversationId: Conversation.Id, taskId: CommandTask.Id): CommandTask? =
            tasks[taskId]?.takeIf { it.conversationId == conversationId }
        override suspend fun publishSnapshot(conversationId: Conversation.Id) = Unit
        override suspend fun synchronizeCommandMonitor(monitor: CommandMonitor, events: List<CommandMonitorEvent>): CommandMonitorSyncResult = error("unused")
        override suspend fun findCommandMonitors(): List<CommandMonitor> = emptyList()
        override suspend fun findCommandMonitor(conversationId: Conversation.Id, monitorId: CommandMonitor.Id): CommandMonitor? = null
        override suspend fun findCommandMonitorEvents(conversationId: Conversation.Id, monitorId: CommandMonitor.Id): List<CommandMonitorEvent> = emptyList()
    }
}
