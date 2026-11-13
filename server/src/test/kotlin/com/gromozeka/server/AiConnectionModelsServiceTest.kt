package com.gromozeka.server

import com.gromozeka.application.service.RuntimeCatalogTemplateApplicationService
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Instant

class AiConnectionModelsServiceTest {
    private val owner = testControlMcpCaller().user
    private val context = ServerToolContext(owner).toExecutionContext()
    private val result = AiConnectionModels(listOf(AiModelCandidate("account/opaque-route")), Instant.parse("2026-11-07T00:00:00Z"))

    @Test
    fun `listing resolves only saved connection and never creates a spec or mutates the catalog`() = runBlocking<Unit> {
        val config = Configuration(connection())
        val before = config.snapshot
        var executions = 0
        val service = AiConnectionModelsService(config, AiConnectionModelsExecutor { selected, deadline ->
            executions++
            assertEquals(connection(), selected)
            assertTrue(deadline > Clock.System.now())
            result
        }, UnusedRemote(), Workers())
        val tool = AiConnectionModelsToolContributor(testServerToolFactory(), service).callbacks.single()
        val call = tool.callResult("""{"connectionId":"models-only","baseUrl":"http://forged.invalid","workerId":"wrong-worker"}""", context)
        assertFalse(call.isError)
        assertEquals(AiToolAccessPolicy.SERVER_OWNER, tool.metadata.accessPolicy)
        assertEquals(true, tool.definition.annotations?.readOnlyHint)
        assertEquals(1, executions)
        assertEquals(1, config.refreshes)
        assertSame(before, config.snapshot)
        assertEquals(0, config.mutations)
        assertFalse(config.snapshot.catalog.modelConfigurations.any { it.connectionId == connection().id })
        assertFalse(config.snapshot.catalog.modelSpecs.any { it.id == "account/opaque-route" })
        val data = call.structuredContent!!.getValue("result").jsonObject
        assertEquals(JsonPrimitive(7), data["catalogRevision"])
        assertEquals(JsonPrimitive(false), data["inferenceChecked"])
        assertEquals(JsonPrimitive(false), data["catalogChanged"])
        assertEquals("account/opaque-route", data.getValue("result").jsonObject.getValue("models").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertFalse(data.toString().contains("synthetic-models-service-secret"))
    }

    @Test
    fun `configured Worker and authenticated actor are used without a Server fallback`() = runBlocking<Unit> {
        val selected = connection().copy(executionTarget = AiExecutionTarget.Worker("exact-worker"))
        val config = Configuration(selected)
        var calls = 0
        val remote = object : UnusedRemote() {
            override suspend fun listModels(target: ConversationRuntimeWorkerIdentity, connection: AiConnection, deadline: Instant, actorUserId: User.Id): AiConnectionModels {
                calls++
                assertEquals("exact-worker", target.workerId.value)
                assertEquals(selected, connection); assertEquals(owner.id, actorUserId)
                assertTrue(deadline > Clock.System.now())
                return result
            }
        }
        val workers = Workers()
        val inspection = AiConnectionModelsService(config, AiConnectionModelsExecutor { _, _ -> error("Must not execute on Server") }, remote, workers)
            .listModels(selected.id, owner.id)
        assertEquals(selected.executionTarget, inspection.executionTarget)
        assertEquals(1, calls); assertEquals(listOf("exact-worker"), workers.selected)
        assertEquals(0, config.mutations)
    }

    @Test
    fun `disabled missing implicit and unsupported connections fail before selecting an executor`() = runBlocking<Unit> {
        for (selected in listOf(connection().copy(enabled = false), AiConnection.OpenAiApi(AiConnection.Id("models-only"), "Native", enabled = true),
            AiConnection.OpenAiSubscription(AiConnection.Id("models-only"), "Subscription", enabled = true))) {
            val workers = Workers()
            val service = AiConnectionModelsService(Configuration(selected), AiConnectionModelsExecutor { _, _ -> error("Unexpected HTTP") }, UnusedRemote(), workers)
            assertFailsWith<IllegalArgumentException> { service.listModels(selected.id, owner.id) }
            assertFailsWith<IllegalArgumentException> { service.listModels(AiConnection.Id("missing"), owner.id) }
            assertTrue(workers.selected.isEmpty())
        }
    }

    @Test
    fun `owner permission is enforced before any discovery execution`() = runBlocking<Unit> {
        var executions = 0
        val config = Configuration(connection())
        val service = AiConnectionModelsService(config, AiConnectionModelsExecutor { _, _ -> executions++; result }, UnusedRemote(), Workers())
        val tool = AiConnectionModelsToolContributor(testServerToolFactory(), service).callbacks.single()
        val member = testControlMcpCaller(User.Role.MEMBER)
        val response = tool.callResult("""{"connectionId":"models-only"}""",
            ToolExecutionContext(mapOf(TOOL_CONTEXT_USER_ID to member.user.id.value)))
        assertTrue(response.isError)
        assertEquals(JsonPrimitive("forbidden"), response.structuredContent!!.getValue("error").jsonObject["code"])
        assertEquals(0, config.refreshes); assertEquals(0, executions)
    }

    @Test
    fun `typed operational failure remains a structured failure without private causes`() = runBlocking<Unit> {
        val failure = AiCallFailure(AiCallFailure.Kind.AUTHENTICATION, AiCallFailure.Outcome.REJECTED, 1, 401)
        val service = AiConnectionModelsService(Configuration(connection()), AiConnectionModelsExecutor { _, _ ->
            throw AiCallException(failure).apply { addSuppressed(IllegalStateException("private-discovery-cause")) }
        }, UnusedRemote(), Workers())
        val response = AiConnectionModelsToolContributor(testServerToolFactory(), service).callbacks.single()
            .callResult("""{"connectionId":"models-only"}""", context)
        assertTrue(response.isError)
        val error = response.structuredContent!!.getValue("error").jsonObject
        assertEquals(JsonPrimitive("ai_call_failed"), error["code"])
        assertEquals(failure, serverToolJson.decodeFromJsonElement(AiCallFailure.serializer(), error.getValue("aiFailure")))
        assertFalse(response.renderText().contains("private-discovery-cause"))
    }

    @Test
    fun `trusted tool cancellation reaches an active operation even through synchronous callback entry`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val service = AiConnectionModelsService(Configuration(connection()), AiConnectionModelsExecutor { _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { finished.complete(Unit) }
        }, UnusedRemote(), Workers())
        val tool = AiConnectionModelsToolContributor(testServerToolFactory(), service).callbacks.single()
        val cancelled = AtomicBoolean(false)
        val signal = ToolCancellationSignal { if (cancelled.get()) throw CancellationException("User cancelled inspection") }
        val input = """{"connectionId":"models-only"}"""
        val job = async(Dispatchers.IO) { tool.call(input, context.withCancellationSignal(signal)) }
        try {
            withTimeout(3000) { entered.await() }
            cancelled.set(true)
            assertFailsWith<CancellationException> { withTimeout(3000) { job.await() } }
            withTimeout(3000) { finished.await() }
            assertFailsWith<CancellationException> { tool.callResult(input, context.withCancellationSignal(signal)) }
        } finally { job.cancelAndJoin() }
    }

    private fun connection() = AiConnection.OpenAiCompatible(AiConnection.Id("models-only"), "Models", enabled = true,
        baseUrl = "https://gateway.example/api/llm", apiKey = SecretRef.Inline("synthetic-models-service-secret"))

    private class Configuration(connection: AiConnection) : AiConfigurationService {
        private val initial = RuntimeCatalogTemplateApplicationService().createSeed().aiCatalog.let {
            AiCatalogSnapshot(it.copy(connections = it.connections + connection), 7)
        }
        override val snapshotFlow = MutableStateFlow<AiCatalogSnapshot?>(initial)
        override val snapshot get() = requireNotNull(snapshotFlow.value)
        var refreshes = 0
        var mutations = 0
        override suspend fun refreshIfChanged() { refreshes++ }
        override suspend fun reload() = snapshot
        override fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime = error("Discovery must not resolve a model")
        override suspend fun replaceCatalog(catalog: AiCatalog, expectedRevision: Long, secretMutations: List<AiCatalogSecretMutation>): AiCatalogSnapshot {
            mutations++; error("Discovery must not mutate the catalog")
        }
    }
    private class Workers : ConversationRuntimeWorkerTargetResolver {
        val selected = mutableListOf<String>()
        override suspend fun requireRegistered(workerId: ConversationRuntimeWorkerId, capability: ConversationRuntimeCapability): ConversationRuntimeWorkerIdentity {
            selected += workerId.value
            assertEquals(ConversationRuntimeCapability.AI_REQUEST_RESPONSE, capability)
            return ConversationRuntimeWorkerIdentity(workerId, ConversationRuntimeWorkerSessionId("session"))
        }
        override suspend fun requireOnline(workerId: ConversationRuntimeWorkerId, capability: ConversationRuntimeCapability): ConversationRuntimeWorkerIdentity = error("Use explicit registered executor")
    }
    private open class UnusedRemote : AiRequestResponseExecutionClient {
        override suspend fun inspectContext(target: com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity, runtime: com.gromozeka.domain.service.ResolvedAiRuntime, workspaceRootPath: String?,
            request: com.gromozeka.domain.model.ai.AiRuntimeRequest, allowProviderCount: Boolean): com.gromozeka.domain.model.ai.AiContextInspection = error("Unused inspectContext")
        override suspend fun compact(target: com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity, runtime: com.gromozeka.domain.service.ResolvedAiRuntime, workspaceRootPath: String?,
            request: com.gromozeka.domain.model.ai.AiRuntimeRequest): com.gromozeka.domain.model.ai.AiRuntimeResponse = error("Unused compact")

        override suspend fun listModels(target: ConversationRuntimeWorkerIdentity, connection: AiConnection, deadline: Instant, actorUserId: User.Id): AiConnectionModels = error("Unexpected remote listing")
        override suspend fun call(target: ConversationRuntimeWorkerIdentity, runtime: ResolvedAiRuntime, workspaceRootPath: String?, request: AiRuntimeRequest): AiRuntimeResponse = error("No inference")
        override suspend fun embed(target: ConversationRuntimeWorkerIdentity, runtime: ResolvedAiRuntime, request: AiEmbeddingRequest): AiEmbeddingResponse = error("Unused")
        override suspend fun transcribe(target: ConversationRuntimeWorkerIdentity, runtime: ResolvedAiRuntime?, localWhisperSettings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper?, request: AiSpeechTranscriptionRequest): String = error("Unused")
        override suspend fun synthesize(target: ConversationRuntimeWorkerIdentity, runtime: ResolvedAiRuntime, request: AiSpeechSynthesisRequest): AiSpeechSynthesisResponse = error("Unused")
        override suspend fun readSubscriptionQuota(target: ConversationRuntimeWorkerIdentity, request: AiSubscriptionQuotaRequest): AiSubscriptionQuotaSnapshot = error("Unused")
    }
}
