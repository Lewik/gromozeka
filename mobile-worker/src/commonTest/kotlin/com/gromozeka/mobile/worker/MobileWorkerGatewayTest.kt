package com.gromozeka.mobile.worker

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ToolCall
import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.domain.model.WorkerPlatform
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkerEnvironmentProfile
import com.gromozeka.domain.service.WorkerNativeShell
import com.gromozeka.domain.service.WorkerOperatingSystem
import com.gromozeka.domain.service.WorkerRequestDelivery
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.remote.protocol.WorkerToolExecutionRequest
import com.gromozeka.worker.runtime.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MobileWorkerGatewayTest {
    private class Store : WorkerRequestSnapshotStore {
        private var snapshot: String? = null
        override suspend fun read() = snapshot
        override suspend fun write(snapshot: String) { this.snapshot = snapshot }
    }
    private class Connection : WorkerGatewayConnection {
        val incoming = Channel<WorkerGatewayMessage>(Channel.UNLIMITED)
        val outgoing = Channel<WorkerGatewayMessage>(Channel.UNLIMITED)
        override suspend fun send(message: WorkerGatewayMessage) { outgoing.send(message) }
        override suspend fun receive() = incoming.receiveCatching().getOrThrow()
        override suspend fun close() { incoming.close() }
    }

    @Test
    fun `mobile composition advertises tools replays saved results and gates disabled enrollment`() = runTest {
        val enrollment = MobileWorkerGatewayEnrollment("https://server.test", "android", "stream", "secret")
        val store = Store()
        var enabled = true
        var executions = 0
        val tool = WorkerDeviceStatusTool {
            executions++
            WorkerDeviceStatus(Clock.System.now(), DeviceStateEvent.DeviceInfo(WorkerPlatform.ANDROID, "Phone", "Android", "1"),
                null, false, null, 10, 0)
        }
        val profile = WorkerEnvironmentProfile(Clock.System.now(), WorkerOperatingSystem(WorkerOperatingSystem.Family.LINUX, "Android", "1"),
            "arm64", WorkerNativeShell(WorkerNativeShell.Kind.POSIX_SH, "/system/bin/sh"), "UTC", "en", 1, null, emptyList())
        val request = WorkerGatewayMessage.Request("request", WorkerGatewayOperation.TOOL_EXECUTION,
            Json.encodeToString(WorkerToolExecutionRequest(
                ConversationRuntimeTaskTarget.Worker(ConversationRuntimeWorkerId("android")),
                listOf(ToolCall(ToolCall.Id("call"), ToolCall.Data(tool.descriptor.definition.name, JsonObject(emptyMap())))), emptyMap(),
            )).encodeToByteArray(), WorkerRequestDelivery(Clock.System.now() + 1.minutes, 10_000))
        val states = mutableListOf<MobileWorkerGatewayState>()
        fun gateway(connection: Connection) = MobileWorkerGateway(enrollment, WorkerGatewayTransport { connection },
            SnapshotWorkerRequestJournal(store), profile, "1", listOf(tool),
            beforeExecution = { check(enabled) { "Enrollment disabled" } }, onState = { states += it })
        suspend fun handshake(connection: Connection) {
            val hello = assertIs<WorkerGatewayMessage.Hello>(connection.outgoing.receive())
            assertEquals("android", hello.registration.identity.workerId.value)
            connection.incoming.send(WorkerGatewayMessage.Welcome(30, catalog(), emptyList()))
            assertEquals(listOf(tool.descriptor), assertIs<WorkerGatewayMessage.Ready>(connection.outgoing.receive()).tools)
        }
        val first = Connection()
        val firstJob = launch { gateway(first).run() }
        val response = try {
            handshake(first)
            first.incoming.send(request)
            assertIs<WorkerGatewayMessage.Response>(first.outgoing.receive()).also {
                assertEquals(WorkerGatewayMessage.Response.Status.SUCCEEDED, it.status)
            }
        } finally { firstJob.cancelAndJoin() }
        assertTrue(MobileWorkerGatewayState.CONNECTED in states)
        assertEquals(MobileWorkerGatewayState.RETRYING, states.last())
        val second = Connection()
        val secondJob = launch { gateway(second).run() }
        try {
            handshake(second)
            assertEquals(response, second.outgoing.receive())
            second.incoming.send(request)
            assertEquals(response, second.outgoing.receive())
            assertEquals(1, executions)
            second.incoming.send(WorkerGatewayMessage.ResponseAcknowledged(request.id))
            runCurrent()
            assertEquals(WorkerRequestReceipt.State.ACKNOWLEDGED, SnapshotWorkerRequestJournal(store).load().single().state)
            enabled = false
            second.incoming.send(request.copy(id = "disabled"))
            assertEquals(WorkerGatewayMessage.Response.Status.FAILED, assertIs<WorkerGatewayMessage.Response>(second.outgoing.receive()).status)
            assertEquals(1, executions)
        } finally { secondJob.cancelAndJoin() }
    }

    @Test
    fun `gateway origin rejects credential leakage and insecure transport`() {
        for (url in listOf("http://server.test", "https://user:password@server.test", "https://server.test/path", "https://server.test?secret=value")) {
            assertFailsWith<IllegalArgumentException> { MobileWorkerGatewayEnrollment(url, "worker", "stream", "credential").gatewayUrl }
        }
    }

    private fun catalog(): AiCatalogSnapshot {
        val connection = AiConnection.OpenAiApi(AiConnection.Id("connection"), "Test", true)
        val configuration = AiModelConfiguration(AiModelConfiguration.Id("model"), connection.id, "test", "Test")
        val spec = AiModelSpec("test", AiProvider.OPENAI, AiModelCapability.entries.toSet(),
            limits = AiModelSpec.Limits(textGeneration = AiModelSpec.Limits.TextGeneration(contextWindowTokens = 1024),
                embeddings = AiModelSpec.Limits.Embeddings(dimensions = 8)))
        return AiCatalogSnapshot(AiCatalog(listOf(connection), listOf(spec), listOf(configuration),
            AiRuntimeAssignment.Purpose.entries.filter { it.requiresExplicitAssignment }.map { AiRuntimeAssignment(it, AiRuntimeSelection(configuration.id)) },
            AgentDefinition.Id("agent")), 1)
    }
}
