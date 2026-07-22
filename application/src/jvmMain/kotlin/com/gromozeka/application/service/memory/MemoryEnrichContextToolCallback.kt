package com.gromozeka.application.service.memory

import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.TOOL_CONTEXT_TARGET_MESSAGE_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component

@Component
class MemoryEnrichContextToolCallback(
    private val memoryOperations: MemoryAsyncOperationApplicationService,
) : AiToolCallback {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class Input(
        val target: String = "previous_user_message",
        val target_message_id: String? = null,
        val context: String? = null,
        val mode: String? = null,
    )

    override val definition: AiToolDefinition = AiToolDefinition(
        name = MEMORY_ENRICH_CONTEXT_TOOL_NAME,
        description = "Queue enrichment of a target context with relevant persisted memory from the global namespace and return a run_id immediately. Follow the returned result_delivery contract: Gromozeka delivers memory_context automatically, while external callers poll memory_run_status. Do not ask this tool a question expecting an answer. Provide the current turn, action item context, topic, or phrase that should be enriched.",
        inputSchema = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "target": {
                  "type": "string",
                  "description": "Context selection mode. Supports 'previous_user_message', 'message_id', and 'provided_context'."
                },
                "target_message_id": {
                  "type": "string",
                  "description": "Optional explicit message id in the current thread to enrich."
                },
                "context": {
                  "type": "string",
                  "description": "Standalone target context to enrich when target='provided_context'. This is a topic, phrase, current turn, or action item context, not a question to answer."
                },
                "mode": {
                  "type": "string",
                  "description": "Optional caller mode label for debugging or analytics."
                }
              }
            }
        """.trimIndent()
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        val input = parseInput(toolInput)
        val resultDelivery = context.memoryOperationResultDeliveryOrNull()
        val providedContext = input.context?.trim().orEmpty()
        if (input.target == "provided_context" || providedContext.isNotBlank()) {
            if (providedContext.isBlank()) {
                return@runBlocking MemoryToolResultRenderer.failureJsonString(
                    "target='provided_context' requires non-blank context."
                )
            }
            return@runBlocking memoryOperations.enrichProvidedContext(
                conversationIdValue = context?.getString("conversationId"),
                contextText = providedContext,
                mode = input.mode,
                resultDelivery = resultDelivery,
            )
        }

        val conversationId = context?.getString("conversationId")
            ?: return@runBlocking MemoryToolResultRenderer.failureJsonString(
                "conversationId not found in ToolExecutionContext. target='${input.target}' can only run inside a conversation turn. Use target='provided_context' with context for standalone enrichment."
            )

        if (input.target !in setOf("previous_user_message", "message_id")) {
            return@runBlocking MemoryToolResultRenderer.failureJsonString("Unsupported memory_enrich_context target: ${input.target}")
        }

        memoryOperations.enrichMessage(
            conversationIdValue = conversationId,
            targetMessageId = input.target_message_id ?: context.getString(TOOL_CONTEXT_TARGET_MESSAGE_ID),
            resultDelivery = resultDelivery,
        )
    }

    private fun parseInput(toolInput: String): Input {
        if (toolInput.isBlank() || toolInput == "{}") {
            return Input()
        }
        return json.decodeFromString(toolInput)
    }
}
