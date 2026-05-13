package com.gromozeka.application.service.memory

import com.gromozeka.application.service.MemoryToolApplicationService
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component

@Component
class MemoryRecallToolCallback(
    private val memoryToolApplicationService: MemoryToolApplicationService,
) : AiToolCallback {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class Input(
        val target: String = "previous_user_message",
        val target_message_id: String? = null,
        val text: String? = null,
        val query: String? = null,
        val mode: String? = null,
    )

    override val definition: AiToolDefinition = AiToolDefinition(
        name = MEMORY_RECALL_TOOL_NAME,
        description = "Read persisted memory relevant to the current turn or to provided standalone query text. Use previous_user_message/message_id inside a conversation. Use provided_text with text or query when calling from an external MCP client without conversation context.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "target": {
                  "type": "string",
                  "description": "Message selection mode. Supports 'previous_user_message', 'message_id', and 'provided_text'."
                },
                "target_message_id": {
                  "type": "string",
                  "description": "Optional explicit message id in the current thread to use as the recall target."
                },
                "text": {
                  "type": "string",
                  "description": "Standalone recall query text for target='provided_text'."
                },
                "query": {
                  "type": "string",
                  "description": "Alias for text when target='provided_text'."
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
        val providedText = input.text?.trim().orEmpty().ifBlank { input.query?.trim().orEmpty() }
        if (input.target == "provided_text" || providedText.isNotBlank()) {
            if (providedText.isBlank()) {
                return@runBlocking MemoryToolResultRenderer.failureJsonString(
                    "target='provided_text' requires non-blank text or query."
                )
            }
            return@runBlocking memoryToolApplicationService.recallProvidedText(
                conversationIdValue = context?.getString("conversationId"),
                text = providedText,
                mode = input.mode,
            )
        }

        val conversationId = context?.getString("conversationId")
            ?: return@runBlocking MemoryToolResultRenderer.failureJsonString(
                "conversationId not found in ToolExecutionContext. target='${input.target}' can only run inside a conversation turn. Use target='provided_text' with text or query for standalone recall."
            )

        if (input.target !in setOf("previous_user_message", "message_id")) {
            return@runBlocking MemoryToolResultRenderer.failureJsonString("Unsupported memory_recall target: ${input.target}")
        }

        memoryToolApplicationService.recall(
            conversationIdValue = conversationId,
            targetMessageId = input.target_message_id,
        )
    }

    private fun parseInput(toolInput: String): Input {
        if (toolInput.isBlank() || toolInput == "{}") {
            return Input()
        }
        return json.decodeFromString(toolInput)
    }
}
