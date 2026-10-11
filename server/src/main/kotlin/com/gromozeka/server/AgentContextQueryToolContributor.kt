package com.gromozeka.server

import com.gromozeka.application.service.AgentContextQueryService
import com.gromozeka.domain.model.AGENT_CONTEXT_QUERY_TOOL_NAME
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["gromozeka.collaboration.enabled"], havingValue = "true")
class AgentContextQueryToolContributor(private val queries: ObjectProvider<AgentContextQueryService>) : AiToolCallbackContributor {
    // Resolve the service only when called; catalog assembly must not create a contributor -> snapshot -> catalog cycle.
    override val callbacks: List<AiToolCallback> = listOf(object : AiToolCallback {
        override val definition = AiToolDefinition(
            AGENT_CONTEXT_QUERY_TOOL_NAME,
            "Ask one tool-free, isolated question using an eligible existing agent session's available context and its selected model. Does not start its turn, send messages or create an obligation. The snapshot pins a complete history prefix; runtime observations are not globally atomic. Answers may say information is missing. Missing snapshot data, unsupported backends (including Copilot), and provider failures are operational errors, without fallback. target_agent_id is required if the conversation has several agents. No cache hit is guaranteed.",
            """{"type":"object","properties":{"target_conversation_id":{"type":"string","minLength":1},"target_agent_id":{"type":["string","null"],"minLength":1},"question":{"type":"string","minLength":1,"maxLength":32000}},"required":["target_conversation_id","question"],"additionalProperties":false}""",
        )
        override val metadata = AiToolMetadata(executionScope = AiToolExecutionScope.SERVER,
            loadingPolicy = AiToolLoadingPolicy.PRELOAD_WHEN_AVAILABLE, visibleToMemoryPipeline = false, logInput = false)
        override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
            val input = Json.parseToJsonElement(toolInput).jsonObject
            require(input.keys.all { it in setOf("target_conversation_id", "target_agent_id", "question") }) { "Unexpected tool arguments" }
            fun text(key: String, optional: Boolean = false): String? {
                val value = input[key]
                if (optional && (value == null || value == JsonNull)) return null
                require(value is JsonPrimitive && value.isString && value.content.isNotBlank()) { "$key must be a nonblank string" }
                return value.content
            }
            val target = requireNotNull(text("target_conversation_id"))
            val question = requireNotNull(text("question"))
            val agent = text("target_agent_id", optional = true)
            require(question.length <= 32_000) { "Question must contain at most 32000 characters" }
            context?.cancellationSignal?.throwIfCancellationRequested()
            val result = queries.getObject().query(context, target, question, agent)
            val snapshot = result.snapshot
            buildJsonObject {
                put("answer", result.answer)
                put("snapshot", buildJsonObject {
                    put("conversation_id", snapshot.target.conversationId.value)
                    put("thread_id", snapshot.target.threadId.value)
                    put("agent_id", snapshot.target.agentId.value)
                    put("captured_at", snapshot.capturedAt.toString())
                    put("last_included_message_id", snapshot.lastIncludedMessageId?.value?.let(::JsonPrimitive) ?: JsonNull)
                    put("source_message_count", snapshot.sourceMessageCount)
                    put("omitted_incomplete_tail_count", snapshot.omittedTrailingMessageCount)
                    put("globally_atomic", false)
                })
            }.toString()
        }
    })
}
