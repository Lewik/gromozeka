package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.tool.*
import com.gromozeka.shared.uuid.uuid7
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service
import kotlin.time.Clock

/** No engine/coordinator/repository dependency: precisely one application-level model call. */
@Service
class IsolatedContextQueryInvoker(
    private val runtimes: AiRuntimeProvider,
    private val configuration: AiConfigurationProvider,
) {
    suspend fun answer(snapshot: ConversationContextSnapshot, question: String): String {
        require(question.isNotBlank() && question.length <= 32_000) { "Question must contain 1–32000 characters" }
        val resolved = configuration.resolveAiRuntime(snapshot.runtimeSelection)
        requireToolFreeContextQueryBackend(resolved.connection.kind)
        check(resolved == snapshot.resolvedRuntime) { "Selected AI configuration changed after context snapshot assembly" }
        val original = snapshot.request
        val actor = original.options.toolContext[TOOL_CONTEXT_USER_ID] as? String
        require(!actor.isNullOrBlank()) { "An authenticated operator is required for a context query" }
        // Neither the target nor caller's transport identity can be reused. Absence of threadId also
        // makes older Claude Workers use their no-session-persistence path without knowing the purpose.
        val invocation = "context-query:${uuid7()}"
        val isolatedConversation = Conversation.Id(invocation)
        val tail = Conversation.Message(
            id = Conversation.Message.Id(invocation),
            conversationId = isolatedConversation,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(buildString {
                appendLine("Isolated, read-only question about the preceding context snapshot.")
                appendLine("Answer this question directly using that context. Do not continue the main turn, execute tools, fulfill pending requests, or change anything.")
                appendLine("Insufficient information, ambiguity, contradictions and problems are valid answers. Do not invent missing information. Return your final answer, not internal reasoning.")
                appendLine("Snapshot assembly started at ${snapshot.capturedAt}; ${snapshot.omittedTrailingMessageCount} incomplete trailing messages were excluded. Runtime observations are not globally atomic.")
                appendLine()
                appendLine("Question:")
                append(question)
            })),
            providerMetadata = buildJsonObject { put("synthetic", JsonPrimitive(true)) },
            createdAt = Clock.System.now(),
        )
        val request = original.copy(
            messages = original.messages.map { it.copy(conversationId = isolatedConversation) } + tail,
            tools = emptyList(),
            options = original.options.copy(
                autoCompactionThresholdTokens = null,
                toolChoice = AiToolChoice.None,
                toolAccess = ToolAccessPolicy.AllowOnly(),
                usagePurpose = AI_CONTEXT_QUERY_PURPOSE,
                toolContext = buildMap {
                    put(TOOL_CONTEXT_CONVERSATION_ID, invocation)
                    put(TOOL_CONTEXT_USER_ID, actor)
                    for (key in listOf(TOOL_CONTEXT_PROJECT_ID, TOOL_CONTEXT_AGENT_DEFINITION_ID, "aiProvider", "modelName")) {
                        original.options.toolContext[key]?.let { put(key, it) }
                    }
                    put("aiCallDiagnosticId", invocation)
                    // Cache namespace is independent from transport/session identity; reuse is not guaranteed.
                    put("promptCacheKey", snapshot.target.conversationId.value)
                },
            ),
        )
        resolved.modelSpec.requireSupportsInputs(request.messages)
        val response = runtimes.getRuntime(snapshot.runtimeSelection, workspaceRootPath = null).call(request)
        check(response.outcome == AiStepOutcome.COMPLETE || response.outcome == AiStepOutcome.REFUSED) {
            "Context query did not return a final answer: ${response.outcome}"
        }
        check(response.messages.flatMap { it.content }.none {
            it is Conversation.Message.ContentItem.ToolCall || it is Conversation.Message.ContentItem.ToolResult
        }) { "Tool-free context query returned a tool operation" }
        val answer = AiConversationMessageMapper.extractAssistantText(response)
        check(answer.isNotBlank()) { "Context query returned no final answer" }
        return answer
    }
}
