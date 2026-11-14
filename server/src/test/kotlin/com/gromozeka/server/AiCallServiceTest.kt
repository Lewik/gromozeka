package com.gromozeka.server

import com.gromozeka.application.service.ConversationArtifactApplicationService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Instant

class AiCallServiceTest {
    @Test fun `one call uses only explicit inputs with main provenance but no history or tools`() = runBlocking {
        val f = Fixture()
        val result = f.service.call(f.input, f.context)
        assertTrue(result.success)
        assertEquals(JsonPrimitive("answer"), result.output)
        assertEquals(f.response.usage, result.usage)
        val sent = f.calls.single()
        assertEquals(listOf("Extract the result"), sent.systemPrompts)
        assertEquals(listOf(ContentItem.UserMessage("  input\n")), sent.messages.single().content)
        assertEquals(f.user.id.value, sent.options.toolContext[TOOL_CONTEXT_USER_ID])
        assertEquals(f.conversation.id.value, sent.options.toolContext[TOOL_CONTEXT_CONVERSATION_ID])
        assertNotEquals(f.conversation.id, sent.messages.single().conversationId)
        assertTrue(sent.messages.single().conversationId.value.startsWith("ai-call:"))
        assertFalse(TOOL_CONTEXT_THREAD_ID in sent.options.toolContext)
        assertFalse("parent-secret" in sent.toString())
        assertTrue(sent.tools.isEmpty())
        assertEquals(AiToolChoice.None, sent.options.toolChoice)
        assertEquals(ToolAccessPolicy.AllowOnly(), sent.options.toolAccess)
        assertEquals(AI_ISOLATED_CALL_PURPOSE, sent.options.usagePurpose)
        assertEquals(AiModelConfiguration.AssistantResponseFormat.TEXT, sent.options.assistantResponseFormat)
        assertTrue(f.artifactRows.isEmpty())
        assertTrue(Mockito.mockingDetails(f.conversations).invocations.all { it.method.name.startsWith("findById") })
        f.service.call(f.input, f.context)
        assertNotEquals(f.calls.first().messages.single().conversationId, f.calls.last().messages.single().conversationId)
    }

    @Test fun `schema is optional validated once and never repaired by another generation`() = runBlocking {
        val f = Fixture()
        val schema = Json.parseToJsonElement("""{"type":"object","required":["answer"],"additionalProperties":false,"properties":{"answer":{"type":"integer"}}}""").jsonObject
        for ((text, status) in listOf("{\"answer\":3}" to "complete", "{\"answer\":\"bad\"}" to "invalid_output",
            "{\"answer\":1,\"answer\":2}" to "invalid_output", "{\"answer\":1} {}" to "invalid_output", "not JSON" to "invalid_output")) {
            f.response = f.response.copy(messages = f.answer(text))
            val before = f.calls.size
            val result = f.service.call(f.input.copy(responseSchema = schema), f.context)
            assertEquals(status, result.status)
            assertEquals(before + 1, f.calls.size)
            assertEquals(schema, (f.calls.last().options.responseFormat as AiResponseFormat.JsonSchema).schema)
            if (status != "complete") assertEquals(JsonPrimitive(text), result.output)
        }
        val before = f.calls.size
        assertFailsWith<IllegalArgumentException> {
            f.service.call(f.input.copy(responseSchema = Json.parseToJsonElement("""{"${'$'}ref":"https://example.test/schema"}""").jsonObject), f.context)
        }
        assertEquals(before, f.calls.size)
    }

    @Test fun `refusal incomplete blank and failed responses retain usage and never report success`() = runBlocking {
        val f = Fixture()
        for ((outcome, status) in listOf(AiStepOutcome.REFUSED to "refused", AiStepOutcome.INCOMPLETE to "incomplete", AiStepOutcome.FAILED to "failed")) {
            f.response = f.response.copy(outcome = outcome)
            val result = f.service.call(f.input, f.context)
            assertFalse(result.success); assertEquals(status, result.status)
            assertEquals(f.response.usage, result.usage)
            assertEquals(JsonPrimitive("answer"), result.output)
        }
        f.response = f.response.copy(outcome = AiStepOutcome.COMPLETE, messages = f.answer(""))
        assertEquals("AI_CALL_EMPTY_RESPONSE", f.service.call(f.input, f.context).errorCode)
        f.response = f.response.copy(messages = listOf(AiAssistantMessage(listOf(ContentItem.Thinking("private reasoning")))))
        assertEquals(JsonPrimitive(""), f.service.call(f.input, f.context).output)
    }

    @Test fun `unexpected tool operations or compaction are not accepted as a final answer`() = runBlocking {
        val f = Fixture()
        val call = ContentItem.ToolCall(ContentItem.ToolCall.Id("call"), ContentItem.ToolCall.Data("tool", JsonObject(emptyMap())))
        f.response = AiRuntimeResponse(listOf(AiAssistantMessage(listOf(call), mapOf(AI_PROVIDER_MANAGED_TOOL_METADATA_KEY to true))), outcome = AiStepOutcome.COMPLETE)
        assertFailsWith<IllegalStateException> { f.service.call(f.input, f.context) }
        assertEquals(1, f.calls.size)
    }

    @Test fun `artifact mode returns only committed reference not text and can feed a later call`() = runBlocking {
        val f = Fixture()
        f.response = f.response.copy(messages = f.answer("full private output"))
        val result = f.service.call(f.input.copy(artifactResult = true), f.context)
        assertNull(result.output)
        val ref = assertNotNull(result.artifact)
        assertEquals(Artifact.State.COMMITTED, f.artifactRows.getValue(ref.id).state)
        assertEquals("full private output", f.artifactBytes.getValue(ref.id).decodeToString())
        f.service.call(f.input.copy(inputs = listOf(AiCallInput("artifact", artifactId = ref.id.value))), f.context)
        assertEquals(listOf(ContentItem.UserMessage("full private output")), f.calls.last().messages.single().content)
    }

    @Test fun `ordered file text image and PDF inputs bypass parent output and enforce exact scope`() = runBlocking {
        val f = Fixture()
        val input = f.input.copy(inputs = listOf(AiCallInput("text", text = "first"),
            AiCallInput("file", workspaceMountId = "mount", path = "data.txt"),
            AiCallInput("file", workspaceMountId = "mount", path = "photo.png"),
            AiCallInput("file", workspaceMountId = "mount", path = "doc.pdf")))
        f.service.call(input, f.context)
        assertEquals(3, f.fileReads.size)
        val content = f.calls.single().messages.single().content
        assertEquals(ContentItem.UserMessage("first"), content[0])
        assertEquals(ContentItem.UserMessage("file text"), content[1])
        assertIs<ContentItem.ImageItem>(content[2]); assertIs<ContentItem.DocumentItem>(content[3])
        assertTrue(f.fileReads.all { it.first.workspaceMountId.value == "mount" && it.second == WorkspacePathAccessContext(f.user.id, f.conversation.projectId) })
        f.resolved = f.resolved.copy(modelSpec = f.resolved.modelSpec.copy(capabilities = setOf(AiModelCapability.TEXT_GENERATION)))
        assertFailsWith<IllegalArgumentException> { f.service.call(input, f.context) }
        assertEquals(1, f.calls.size)
    }

    @Test fun `missing permissions foreign artifacts unsupported backends and oversize inputs fail before inference`() = runBlocking {
        val f = Fixture()
        f.user = f.user.copy(aiAllowed = false)
        assertFailsWith<IllegalArgumentException> { f.service.call(f.input, f.context) }
        f.user = f.user.copy(aiAllowed = true)
        assertFailsWith<IllegalArgumentException> { f.service.call(f.input.copy(prompt = "x".repeat(AiCallService.MAX_PROMPT_CHARACTERS + 1)), f.context) }
        assertFailsWith<IllegalArgumentException> { f.service.call(f.input.copy(inputs = listOf(AiCallInput("text", text = "x".repeat(AiCallService.MAX_INPUT_BYTES.toInt())))), f.context) }
        val foreign = Artifact(Artifact.Id("foreign"), f.conversation.projectId, Conversation.Id("other"), f.user.id, "x.txt", "text/plain", 1,
            Artifact.ContentSource.Managed("a".repeat(64)), Artifact.Purpose.TOOL_OUTPUT, Artifact.State.COMMITTED, f.now, f.now)
        f.artifactRows[foreign.id] = foreign
        assertFailsWith<IllegalArgumentException> { f.service.call(f.input.copy(inputs = listOf(AiCallInput("artifact", artifactId = "foreign"))), f.context) }
        assertTrue(f.artifactBytes.isEmpty())
        f.resolved = f.resolved.copy(connection = AiConnection.GitHubCopilot(f.resolved.connection.id, "Unsupported", enabled = true))
        assertFailsWith<UnsupportedOperationException> { f.service.call(f.input, f.context) }
        assertTrue(f.calls.isEmpty())
    }

    @Test fun `access revocation after input retrieval and cancellation prevent inference`() = runBlocking {
        val f = Fixture()
        f.afterRead = { f.user = f.user.copy(aiAllowed = false) }
        assertFailsWith<IllegalArgumentException> { f.service.call(f.input.copy(inputs = listOf(AiCallInput("file", workspaceMountId = "mount", path = "data.txt"))), f.context) }
        assertTrue(f.calls.isEmpty())
        f.user = f.user.copy(aiAllowed = true)
        assertFailsWith<CancellationException> { f.service.call(f.input, f.context.withCancellationSignal(ToolCancellationSignal { throw CancellationException("stop") })) }
        assertTrue(f.calls.isEmpty())
    }

    @Test fun `model Worker access output caps and late revocation are enforced`() = runBlocking {
        val f = Fixture()
        f.resolved = f.resolved.copy(connection = (f.resolved.connection as AiConnection.OpenAiApi).copy(executionTarget = AiExecutionTarget.Worker("model-worker")))
        f.service.call(f.input.copy(maxOutputTokens = 123), f.context)
        assertEquals(123, f.calls.single().options.maxOutputTokens)
        Mockito.verify(f.workers, Mockito.atLeastOnce()).requirePermission(f.user, ConversationRuntimeWorkerId("model-worker"), WorkerPermission.USE, f.conversation.projectId)
        Mockito.verify(f.workers, Mockito.atLeastOnce()).requireProjectAccess(ConversationRuntimeWorkerId("model-worker"), f.conversation.projectId)
        f.execute = { f.user = f.user.copy(aiAllowed = false); f.response }
        assertFailsWith<IllegalArgumentException> { f.service.call(f.input.copy(artifactResult = true), f.context) }
        assertTrue(f.artifactRows.isEmpty())
        assertEquals(2, f.calls.size)
        f.user = f.user.copy(aiAllowed = true)
        f.resolved = f.resolved.copy(connection = AiConnection.OpenAiSubscription(f.resolved.connection.id, "Subscription", enabled = true))
        assertFailsWith<UnsupportedOperationException> { f.service.call(f.input.copy(maxOutputTokens = 123), f.context) }
        assertEquals(2, f.calls.size)
    }

    internal class Fixture {
        val now = Clock.System.now()
        var user = User(User.Id("user"), displayName = "User", status = User.Status.ACTIVE, createdAt = now, updatedAt = now)
        val conversation = Conversation(Conversation.Id("parent"), Project.Id("project"), setOf(Conversation.Participant.User(user.id), Conversation.Participant.Agent(AgentDefinition.Id("agent"))),
            currentThread = Conversation.Thread.Id("parent-thread"), createdAt = now, updatedAt = now)
        val context = ToolExecutionContext(mapOf(TOOL_CONTEXT_USER_ID to user.id.value, TOOL_CONTEXT_CONVERSATION_ID to conversation.id.value,
            TOOL_CONTEXT_AGENT_DEFINITION_ID to "agent", TOOL_CONTEXT_THREAD_ID to "parent-thread", "parent-secret" to "must not be inherited"))
        val input = AiCallRequest("selected", "Extract the result", listOf(AiCallInput("text", text = "  input\n")))
        var resolved = ResolvedAiRuntime(AiConnection.OpenAiApi(AiConnection.Id("connection"), "Connection", enabled = true),
            AiModelConfiguration(AiModelConfiguration.Id("selected"), AiConnection.Id("connection"), "model", "Model"),
            AiModelSpec("model", AiProvider.OPENAI, setOf(AiModelCapability.TEXT_GENERATION, AiModelCapability.STRUCTURED_OUTPUT, AiModelCapability.IMAGE_INPUT, AiModelCapability.DOCUMENT_INPUT),
                AiModelSpec.Limits(textGeneration = AiModelSpec.Limits.TextGeneration(100000, 10000))))
        val users = Mockito.mock(UserDirectoryService::class.java)
        val conversations = Mockito.mock(ConversationRepository::class.java)
        val projects = Mockito.mock(ProjectAccessService::class.java)
        val workers = Mockito.mock(WorkerAccessService::class.java)
        val configurations = Mockito.mock(AiConfigurationProvider::class.java)
        val runtimes = Mockito.mock(AiRuntimeProvider::class.java)
        val calls = mutableListOf<AiRuntimeRequest>()
        var response = AiRuntimeResponse(answer("answer"), usage = AiUsage(10, 2))
        var execute: suspend (AiRuntimeRequest) -> AiRuntimeResponse = { response }
        val runtime = object : AiRuntime {
            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse { calls += request; return execute(request) }
            override fun stream(request: AiRuntimeRequest) = emptyFlow<AiRuntimeResponse>()
        }
        val fileReads = mutableListOf<Pair<WorkspacePathReference, WorkspacePathAccessContext>>()
        var afterRead: () -> Unit = {}
        val files = WorkspaceFileReader { reference, access, max ->
            fileReads += reference to access
            assertTrue(max in 1..AiCallService.MAX_INPUT_BYTES)
            afterRead()
            val bytes = if (reference.path.endsWith("txt")) "file text".encodeToByteArray() else byteArrayOf(0, 1, 2, -1)
            WorkspaceFile(reference, "/not-opened/${reference.path}", reference.path, BinaryContent.fromBytes(bytes), bytes.size.toLong())
        }
        val artifactRows = linkedMapOf<Artifact.Id, Artifact>()
        val artifactBytes = linkedMapOf<Artifact.Id, ByteArray>()
        val repository = object : ArtifactRepository {
            override suspend fun save(artifact: Artifact, searchableText: String?) = artifact.also { artifactRows[it.id] = it }
            override suspend fun cloneBatch(sourceConversationId: Conversation.Id, targetConversation: Conversation,
                sourceToTargetIds: Map<Artifact.Id, Artifact.Id>, createdAt: Instant): List<Artifact> =
                error("An isolated AI call must not clone conversation artifacts")
            override suspend fun findById(id: Artifact.Id) = artifactRows[id]
            override suspend fun findByIds(ids: List<Artifact.Id>) = ids.mapNotNull(artifactRows::get)
            override suspend fun findByConversation(conversationId: Conversation.Id) = artifactRows.values.filter { it.conversationId == conversationId }
            override suspend fun commit(ids: List<Artifact.Id>, committedAt: Instant) { ids.forEach { artifactRows[it] = artifactRows.getValue(it).copy(state = Artifact.State.COMMITTED, committedAt = committedAt) } }
            override suspend fun deleteDraft(id: Artifact.Id) = artifactRows.remove(id) != null
            override suspend fun findDraftsCreatedBefore(createdBefore: Instant, limit: Int) = emptyList<Artifact>()
        }
        val contentStore = object : ArtifactContentStore {
            override suspend fun write(id: Artifact.Id, content: ByteArray) { artifactBytes[id] = content }
            override suspend fun copy(sourceId: Artifact.Id, targetId: Artifact.Id): Unit =
                error("An isolated AI call must not copy conversation artifacts")
            override suspend fun read(id: Artifact.Id) = artifactBytes.getValue(id)
            override suspend fun delete(id: Artifact.Id) { artifactBytes.remove(id) }
            override suspend fun listIds() = artifactBytes.keys.toSet()
        }
        val artifacts = ConversationArtifactApplicationService(repository, contentStore)
        val service = AiCallService(configurations, runtimes, users, conversations, projects, workers, files, artifacts, repository)
        init { runBlocking {
            Mockito.`when`(users.findActiveById(user.id)).thenAnswer { user }
            Mockito.`when`(conversations.findById(conversation.id)).thenReturn(conversation)
            Mockito.`when`(configurations.resolveAiRuntime(AiRuntimeSelection(resolved.modelConfiguration.id))).thenAnswer { resolved }
            Mockito.`when`(runtimes.getRuntime(AiRuntimeSelection(resolved.modelConfiguration.id), null)).thenReturn(runtime)
        } }
        fun answer(text: String) = listOf(AiAssistantMessage(listOf(ContentItem.AssistantMessage(Conversation.Message.StructuredText(text)))))
    }
}
