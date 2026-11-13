package com.gromozeka.worker

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.*
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import java.nio.file.Path
import kotlin.test.*
import kotlin.time.Instant

class WorkerCommandScriptBootstrapServiceTest {
    @Test fun `bootstrap derives endpoint and CA without exposing the Worker credential`() {
        val credential = "synthetic-worker-credential-never-delegate"
        val ca = Path.of("trust", "corporate ca.pem")
        val service = WorkerCommandScriptBootstrapService(
            WorkerGatewayProperties(serverUrl = "wss://server.example:9443", credential = credential, caCertificatePath = ca.toString()),
            Mockito.mock(WorkerGatewayOutbound::class.java),
        )
        val first = service.create()
        val second = service.create()
        assertEquals("https://server.example:9443/mcp", first.environment["GRZ_MCP_URL"])
        assertEquals(ca.toAbsolutePath().normalize().toString(), first.environment["GRZ_MCP_CA_BUNDLE"])
        assertTrue(CommandScriptTokens.isCommandToken(first.environment.getValue("GRZ_MCP_TOKEN")))
        assertEquals(CommandScriptTokens.hash(first.environment.getValue("GRZ_MCP_TOKEN")), first.tokenHash)
        assertNotEquals(first.tokenHash, second.tokenHash)
        assertFalse(first.environment.toString().contains(credential))
        assertFalse(first.toString().contains(first.environment.getValue("GRZ_MCP_TOKEN")))
    }

    @Test fun `plain HTTP is restricted to loopback and missing CA has no invented path`() {
        val outbound = Mockito.mock(WorkerGatewayOutbound::class.java)
        val local = WorkerCommandScriptBootstrapService(WorkerGatewayProperties(serverUrl = "http://127.0.0.1:8765"), outbound).create()
        assertEquals("http://127.0.0.1:8765/mcp", local.environment["GRZ_MCP_URL"])
        assertFalse(local.environment.containsKey("GRZ_MCP_CA_BUNDLE"))
        for (url in listOf("http://remote.example", "https://server.example/path", "https://user:password@server.example")) {
            assertFailsWith<IllegalArgumentException> {
                WorkerCommandScriptBootstrapService(WorkerGatewayProperties(serverUrl = url), outbound).create()
            }
        }
    }

    @Test fun `registration sends only a hash through the authenticated Worker channel`() = runBlocking<Unit> {
        val now = Instant.fromEpochSeconds(1)
        val task = CommandTask(CommandTask.Id("command"), Conversation.Id("conversation"), ConversationRuntimeWorkerId("worker"),
            WorkspaceMount.Id("mount"), command = "synthetic", workingDirectory = "/synthetic",
            status = CommandTask.Status.WORKING, processId = 1, processStartedAt = now, processTreeId = 1,
            outputFile = "/not-opened", outputBytes = 0, createdAt = now, updatedAt = now,
            launchReference = BackgroundActivityLaunchReference("launch", "call"))
        val payloads = mutableListOf<ByteArray>()
        var invalidResponse = false
        val outbound = Mockito.mock(WorkerGatewayOutbound::class.java) { call ->
            if (call.method.name.startsWith("execute")) {
                assertEquals(WorkerGatewayOperation.COMMAND_RUNTIME_STATE, call.arguments[0])
                payloads += call.arguments[1] as ByteArray
                WorkerCommandRuntimeGatewayCodec.encodeResponse(if (invalidResponse) WorkerCommandRuntimeResponse.Completed
                    else WorkerCommandRuntimeResponse.CommandTaskUpserted(task, emptyList()))
            } else Mockito.RETURNS_DEFAULTS.answer(call)
        }
        val service = WorkerCommandScriptBootstrapService(WorkerGatewayProperties(serverUrl = "https://server.example"), outbound)
        val bootstrap = service.create()
        assertEquals(task, service.register(task, bootstrap.tokenHash).task)
        val sent = assertIs<WorkerCommandRuntimeRequest.RegisterCommandTask>(WorkerCommandRuntimeGatewayCodec.decodeRequest(payloads.single()))
        assertEquals(task, sent.task)
        assertEquals(bootstrap.tokenHash, sent.tokenHash)
        assertFalse(payloads.single().decodeToString().contains(bootstrap.environment.getValue("GRZ_MCP_TOKEN")))
        invalidResponse = true
        assertFailsWith<IllegalStateException> { service.register(task, bootstrap.tokenHash) }
        assertEquals(2, payloads.size, "No hidden retries or fallback registrations")
    }
}
