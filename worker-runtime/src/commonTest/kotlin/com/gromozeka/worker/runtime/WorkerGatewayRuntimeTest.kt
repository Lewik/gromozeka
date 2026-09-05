package com.gromozeka.worker.runtime

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerEnvironmentProfile
import com.gromozeka.domain.service.WorkerNativeShell
import com.gromozeka.domain.service.WorkerOperatingSystem
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkerGatewayRuntimeTest {
    @Test
    fun `registration heartbeat and request response use the common runtime`() = runTest {
        val connection = TestConnection()
        val runtime = runtime(connection)
        val job = launch { runtime.run() }
        handshake(connection)
        connection.received.send(request("first"))
        assertEquals("first", assertIs<WorkerGatewayMessage.Response>(connection.sent.receive()).requestId)
        advanceTimeBy(1_001)
        assertIs<WorkerGatewayMessage.Heartbeat>(connection.sent.receive())
        connection.received.close()
        job.cancelAndJoin()
        assertTrue(connection.closed)
    }

    @Test
    fun `reconnect preserves worker identity and registers again`() = runTest {
        val connections = Channel<TestConnection>(Channel.UNLIMITED)
        val runtime = runtime(transport = WorkerGatewayTransport {
            TestConnection().also { connections.send(it) }
        })
        val job = launch { runtime.run() }
        val first = connections.receive()
        val firstHello = handshake(first)
        first.received.close()
        runCurrent()
        advanceTimeBy(1_001)
        val second = connections.receive()
        val secondHello = handshake(second)
        assertEquals(firstHello.registration.identity, secondHello.registration.identity)
        connectionRequest(second, "after-reconnect")
        job.cancelAndJoin()
        assertTrue(first.closed)
        assertTrue(second.closed)
    }

    @Test
    fun `active request survives reconnect and saved response is replayed until acknowledged`() = runTest {
        val connections = Channel<TestConnection>(Channel.UNLIMITED)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var executions = 0
        val runtime = runtime(
            transport = WorkerGatewayTransport { TestConnection().also { connections.send(it) } },
            handler = WorkerRequestHandler {
                executions++
                started.complete(Unit)
                finish.await()
                success(it)
            },
        )
        val job = launch { runtime.run() }
        try {
            val first = connections.receive()
            handshake(first)
            val request = request("survive-reconnect")
            first.received.send(request)
            started.await()
            first.received.close()
            runCurrent()
            advanceTimeBy(1_001)
            val second = connections.receive()
            handshake(second)
            second.received.send(request)
            runCurrent()
            assertEquals(1, executions)
            finish.complete(Unit)
            assertEquals(success(request), second.sent.receive())
            second.received.close()
            runCurrent()
            advanceTimeBy(1_001)
            val third = connections.receive()
            handshake(third)
            assertEquals(success(request), third.sent.receive())
            third.received.send(WorkerGatewayMessage.ResponseAcknowledged(request.id))
            runCurrent()
            advanceTimeBy(1_001)
            assertIs<WorkerGatewayMessage.Heartbeat>(third.sent.receive())
            assertTrue(third.sent.tryReceive().isFailure)
            assertEquals(1, executions)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun `handshake timeout reconnects while parent cancellation stops the loop`() = runTest {
        val connections = Channel<TestConnection>(Channel.UNLIMITED)
        val runtime = runtime(transport = WorkerGatewayTransport {
            TestConnection().also { connections.send(it) }
        })
        val job = launch { runtime.run() }
        val first = connections.receive()
        assertIs<WorkerGatewayMessage.Hello>(first.sent.receive())
        advanceTimeBy(16_001)
        val second = connections.receive()
        handshake(second)
        assertTrue(first.closed)
        job.cancelAndJoin()
        advanceTimeBy(60_000)
        assertTrue(connections.tryReceive().isFailure)
    }

    @Test
    fun `different workers can run the same request id independently`() = runTest {
        val first = TestConnection()
        val second = TestConnection()
        val firstJob = launch { runtime(first, id = "first-worker").run() }
        val secondJob = launch { runtime(second, id = "second-worker").run() }
        assertEquals("first-worker", handshake(first).registration.identity.workerId.value)
        assertEquals("second-worker", handshake(second).registration.identity.workerId.value)
        connectionRequest(first, "same-id")
        connectionRequest(second, "same-id")
        firstJob.cancelAndJoin()
        connectionRequest(second, "still-online")
        secondJob.cancelAndJoin()
    }

    @Test
    fun `server cancellation stops only the addressed request`() = runTest {
        val connection = TestConnection()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val job = launch {
            runtime(connection, handler = WorkerRequestHandler {
                if (it.id == "slow") {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
                success(it)
            }).run()
        }
        handshake(connection)
        connection.received.send(request("slow"))
        started.await()
        connection.received.send(WorkerGatewayMessage.CancelRequest("slow"))
        cancelled.await()
        assertEquals("slow", assertIs<WorkerGatewayMessage.Response>(connection.sent.receive()).requestId)
        connectionRequest(connection, "fast")
        job.cancelAndJoin()
    }

    @Test
    fun `disconnect preserves execution and fails only connection scoped outbound calls`() = runTest {
        val connection = TestConnection()
        val outbound = WorkerGatewayOutbound(capabilities)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val job = launch {
            runtime(connection, outbound = outbound, handler = WorkerRequestHandler {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }).run()
        }
        handshake(connection)
        connection.received.send(request("slow"))
        started.await()
        val response = async {
            runCatching { outbound.execute(WorkerGatewayOperation.WORKSPACE_STATE, byteArrayOf(1)) }
        }
        assertIs<WorkerGatewayMessage.Request>(connection.sent.receive())
        connection.received.close()
        runCurrent()
        assertFalse(cancelled.isCompleted)
        job.cancelAndJoin()
        cancelled.await()
        assertTrue(response.await().isFailure)
        assertFailsWith<IllegalStateException> { outbound.execute(WorkerGatewayOperation.WORKSPACE_STATE, byteArrayOf()) }
    }

    @Test
    fun `late outbound responses are recognized without killing the connection`() = runTest {
        val outbound = WorkerGatewayOutbound(capabilities)
        val outgoing = Channel<WorkerGatewayMessage>(Channel.UNLIMITED)
        outbound.attach(outgoing)
        val timedOut = async {
            runCatching { outbound.execute(WorkerGatewayOperation.WORKSPACE_STATE, byteArrayOf(), 1.seconds) }
        }
        val request = assertIs<WorkerGatewayMessage.Request>(outgoing.receive())
        advanceTimeBy(1_001)
        assertTrue(timedOut.await().isFailure)
        assertTrue(outbound.accept(success(request)))
        assertFalse(outbound.accept(success(request("unknown"))))
        outbound.detach(outgoing)
    }

    @Test
    fun `stale detach cannot disconnect a replacement connection`() = runTest {
        val outbound = WorkerGatewayOutbound(capabilities)
        val first = Channel<WorkerGatewayMessage>(Channel.UNLIMITED)
        val second = Channel<WorkerGatewayMessage>(Channel.UNLIMITED)
        outbound.attach(first)
        outbound.detach(first)
        outbound.attach(second)
        outbound.detach(first)
        val result = async { outbound.execute(WorkerGatewayOperation.WORKSPACE_STATE, byteArrayOf()) }
        val request = assertIs<WorkerGatewayMessage.Request>(second.receive())
        outbound.accept(success(request))
        assertContentEquals(byteArrayOf(7), result.await())
        outbound.detach(second)
    }

    @Test
    fun `worker registration still rejects server orchestration capabilities`() {
        assertFailsWith<IllegalArgumentException> {
            registration().copy(capabilities = setOf(ConversationRuntimeCapability.CONVERSATION_TURN))
        }
    }

    private suspend fun handshake(connection: TestConnection): WorkerGatewayMessage.Hello {
        val hello = assertIs<WorkerGatewayMessage.Hello>(connection.sent.receive())
        connection.received.send(welcome)
        assertIs<WorkerGatewayMessage.Ready>(connection.sent.receive())
        return hello
    }

    private suspend fun connectionRequest(connection: TestConnection, id: String) {
        connection.received.send(request(id))
        assertEquals(id, assertIs<WorkerGatewayMessage.Response>(connection.sent.receive()).requestId)
    }

    private fun runtime(
        connection: TestConnection = TestConnection(),
        id: String = "worker",
        transport: WorkerGatewayTransport = WorkerGatewayTransport { connection },
        outbound: WorkerGatewayOutbound = WorkerGatewayOutbound(capabilities),
        handler: WorkerRequestHandler = WorkerRequestHandler(::success),
    ) = WorkerGatewayRuntime(
        transport = transport,
        journal = TestWorkerRequestJournal(),
        registration = { registration(id) },
        outbound = outbound,
        handler = handler,
        prepare = { WorkerGatewayMessage.Ready(emptyList()) },
        updateCatalog = {},
        reconnectDelay = 1.seconds,
    )

    private fun registration(id: String = "worker"): ConversationRuntimeWorkerRegistration {
        val time = Instant.parse("2026-09-05T00:00:00Z")
        return ConversationRuntimeWorkerRegistration(
            identity = ConversationRuntimeWorkerIdentity(ConversationRuntimeWorkerId(id), ConversationRuntimeWorkerSessionId("process")),
            capabilities = capabilities,
            tools = emptyList(),
            environmentProfile = WorkerEnvironmentProfile(
                observedAt = time,
                operatingSystem = WorkerOperatingSystem(WorkerOperatingSystem.Family.OTHER, "test", "1"),
                architecture = "test",
                nativeShell = WorkerNativeShell(WorkerNativeShell.Kind.POSIX_SH, "/bin/sh"),
                timezoneId = "UTC",
                localeTag = "en",
                logicalProcessorCount = 1,
                totalMemoryBytes = null,
                availableExecutables = emptyList(),
            ),
            version = "test",
            startedAt = time,
            lastHeartbeatAt = time,
        )
    }

    private class TestConnection : WorkerGatewayConnection {
        val received = Channel<WorkerGatewayMessage>(Channel.UNLIMITED)
        val sent = Channel<WorkerGatewayMessage>(Channel.UNLIMITED)
        var closed = false
        override suspend fun send(message: WorkerGatewayMessage) { sent.send(message) }
        override suspend fun receive(): WorkerGatewayMessage? = received.receiveCatching().getOrNull()
        override suspend fun close() { closed = true }
    }

    private fun request(id: String) = WorkerGatewayMessage.Request(
        id, WorkerGatewayOperation.WORKER_CONTROL, byteArrayOf(),
        com.gromozeka.domain.service.WorkerRequestDelivery(kotlin.time.Clock.System.now() + 30.seconds, 10_000),
    )
    private fun success(request: WorkerGatewayMessage.Request) = WorkerGatewayMessage.Response(
        requestId = request.id,
        status = WorkerGatewayMessage.Response.Status.SUCCEEDED,
        payload = byteArrayOf(7),
    )

    private fun testAiCatalog(): AiCatalogSnapshot {
        val connection = AiConnection.OpenAiApi(
            id = AiConnection.Id("test-connection"),
            displayName = "Test connection",
            enabled = true,
        )
        val configuration = AiModelConfiguration(
            id = AiModelConfiguration.Id("test-model"),
            connectionId = connection.id,
            providerModelId = "test-model",
            displayName = "Test model",
        )
        val modelSpec = AiModelSpec(
            id = configuration.providerModelId,
            provider = AiProvider.OPENAI,
            capabilities = AiModelCapability.entries.toSet(),
            limits = AiModelSpec.Limits(
                textGeneration = AiModelSpec.Limits.TextGeneration(
                    contextWindowTokens = 1_024,
                ),
                embeddings = AiModelSpec.Limits.Embeddings(dimensions = 8),
            ),
        )
        return AiCatalogSnapshot(
            catalog = AiCatalog(
                connections = listOf(connection),
                modelSpecs = listOf(modelSpec),
                modelConfigurations = listOf(configuration),
                runtimeAssignments = AiRuntimeAssignment.Purpose.entries
                    .filter(AiRuntimeAssignment.Purpose::requiresExplicitAssignment)
                    .map {
                        AiRuntimeAssignment(
                            purpose = it,
                            selection = AiRuntimeSelection(configuration.id),
                        )
                    },
                defaultAgentId = AgentDefinition.Id("test-agent"),
            ),
            revision = 1,
        )
    }

    private val capabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION)
    private val welcome = WorkerGatewayMessage.Welcome(
        heartbeatIntervalSeconds = 1,
        aiCatalogSnapshot = testAiCatalog(),
        mcpServers = emptyList(),
    )
}
