package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class IsolatedContextQueryInvokerTest {
    @Test fun `one call preserves prefix overrides and selected Worker but isolates both sessions`() = runBlocking {
        val f = Fixture()
        val before = f.snapshot.request
        assertEquals("Not enough information in this context.", f.service.answer(f.snapshot, "What is known?"))
        val sent = f.calls.single()
        assertEquals(before.systemPrompts, sent.systemPrompts)
        assertEquals(before.options.reasoning, sent.options.reasoning)
        assertEquals(before.options.maxOutputTokens, sent.options.maxOutputTokens)
        assertEquals(before.options.responseFormat, sent.options.responseFormat)
        assertEquals(AI_CONTEXT_QUERY_PURPOSE, sent.options.usagePurpose)
        assertEquals(AiToolChoice.None, sent.options.toolChoice)
        assertEquals(ToolAccessPolicy.AllowOnly(), sent.options.toolAccess)
        assertNull(sent.options.autoCompactionThresholdTokens)
        assertTrue(sent.tools.isEmpty())
        assertEquals("operator", sent.options.toolContext[TOOL_CONTEXT_USER_ID])
        assertEquals("target", sent.options.toolContext["promptCacheKey"])
        assertFalse(TOOL_CONTEXT_THREAD_ID in sent.options.toolContext)
        assertFalse(TOOL_CONTEXT_TARGET_MESSAGE_ID in sent.options.toolContext)
        val isolated = sent.options.toolContext[TOOL_CONTEXT_CONVERSATION_ID] as String
        assertTrue(isolated.startsWith("context-query:"))
        assertNotEquals("target", isolated)
        assertTrue(sent.messages.all { it.conversationId.value == isolated })
        assertEquals(before.messages.size + 1, sent.messages.size)
        assertContains((sent.messages.last().content.single() as Conversation.Message.ContentItem.UserMessage).text, "What is known?")
        assertEquals(before, f.snapshot.request)
        f.service.answer(f.snapshot, "Another question")
        assertNotEquals(isolated, f.calls.last().options.toolContext[TOOL_CONTEXT_CONVERSATION_ID])
    }

    @Test fun `provider failure is not converted to unknown or retried`() = runBlocking {
        val f = Fixture()
        f.failure = IllegalStateException("transport failed")
        val failure = assertFailsWith<IllegalStateException> { f.service.answer(f.snapshot, "Question") }
        assertEquals("transport failed", failure.message)
        assertEquals(1, f.calls.size)
    }

    @Test fun `thinking alone and any tool output are operational failures`() = runBlocking {
        val f = Fixture()
        f.response = AiRuntimeResponse(listOf(AiAssistantMessage(listOf(Conversation.Message.ContentItem.Thinking("private reasoning")))))
        assertFailsWith<IllegalStateException> { f.service.answer(f.snapshot, "Question") }
        f.response = AiRuntimeResponse(listOf(AiAssistantMessage(listOf(
            Conversation.Message.ContentItem.AssistantMessage(Conversation.Message.StructuredText("answer")),
            Conversation.Message.ContentItem.ToolCall(Conversation.Message.ContentItem.ToolCall.Id("native"),
                Conversation.Message.ContentItem.ToolCall.Data("native-tool", JsonObject(emptyMap()))),
        ), metadata = mapOf(AI_PROVIDER_MANAGED_TOOL_METADATA_KEY to true))))
        assertFailsWith<IllegalStateException> { f.service.answer(f.snapshot, "Question") }
        assertEquals(2, f.calls.size)
    }

    @Test fun `disabled or changed target configuration fails before invocation without fallback`() = runBlocking {
        val f = Fixture()
        Mockito.`when`(f.configuration.resolveAiRuntime(f.selection)).thenThrow(IllegalStateException("disabled"))
        assertFailsWith<IllegalStateException> { f.service.answer(f.snapshot, "Question") }
        assertTrue(f.calls.isEmpty())
    }

    @Test fun `unsupported strict backend is explicit`() {
        assertFailsWith<UnsupportedOperationException> { requireToolFreeContextQueryBackend(AiConnection.Kind.GITHUB_COPILOT) }
    }

    private class Fixture {
        val selection = AiRuntimeSelection(AiModelConfiguration.Id("selected"))
        val configuration = Mockito.mock(AiConfigurationProvider::class.java)
        private val connection = AiConnection.OpenAiSubscription(AiConnection.Id("target-connection"), "Target",
            enabled = true, executionTarget = AiExecutionTarget.Worker("target-worker"))
        private val model = AiModelConfiguration(selection.modelConfigurationId, connection.id, "gpt-test", "Test")
        private val resolved = ResolvedAiRuntime(connection, model, AiModelSpec("gpt-test", AiProvider.OPENAI,
            setOf(AiModelCapability.TEXT_GENERATION), AiModelSpec.Limits(textGeneration = AiModelSpec.Limits.TextGeneration(100_000))))
        val snapshot = ConversationContextSnapshot(
            target = AgentEndpoint(Conversation.Id("target"), Conversation.Thread.Id("pinned"), AgentDefinition.Id("agent")),
            runtimeSelection = selection, resolvedRuntime = resolved,
            request = AiRuntimeRequest(listOf("base", "runtime", "skills", "slots", "visual"), listOf(
                Conversation.Message(Conversation.Message.Id("u"), Conversation.Id("target"), role = Conversation.Message.Role.USER,
                    content = listOf(Conversation.Message.ContentItem.UserMessage("history")), createdAt = Instant.fromEpochMilliseconds(0)),
            ), options = AiRuntimeOptions(maxOutputTokens = 2048, reasoning = AiReasoningConfig(effort = AiReasoningEffort.HIGH),
                autoCompactionThresholdTokens = 1000, toolContext = mapOf(TOOL_CONTEXT_USER_ID to "operator",
                    TOOL_CONTEXT_CONVERSATION_ID to "target", TOOL_CONTEXT_THREAD_ID to "pinned", TOOL_CONTEXT_TARGET_MESSAGE_ID to "u",
                    TOOL_CONTEXT_PROJECT_ID to "project", TOOL_CONTEXT_AGENT_DEFINITION_ID to "agent"))),
            capturedAt = Instant.fromEpochMilliseconds(0), sourceMessageCount = 1, omittedTrailingMessageCount = 0,
        )
        val calls = mutableListOf<AiRuntimeRequest>()
        var failure: Exception? = null
        var response = AiRuntimeResponse(listOf(AiAssistantMessage(listOf(
            Conversation.Message.ContentItem.Thinking("private reasoning"),
            Conversation.Message.ContentItem.AssistantMessage(Conversation.Message.StructuredText("Not enough information in this context.")),
        ))))
        val service = IsolatedContextQueryInvoker(object : AiRuntimeProvider {
            override fun getRuntime(selection: AiRuntimeSelection, workspaceRootPath: String?): AiRuntime {
                assertEquals(this@Fixture.selection, selection)
                assertNull(workspaceRootPath)
                return object : AiRuntime {
                    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
                        calls += request
                        failure?.let { throw it }
                        return response
                    }
                    override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = error("Must call once, not stream")
                }
            }
        }, configuration)
        init { Mockito.`when`(configuration.resolveAiRuntime(selection)).thenReturn(resolved) }
    }
}
