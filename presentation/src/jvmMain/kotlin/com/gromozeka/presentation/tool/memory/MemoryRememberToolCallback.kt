package com.gromozeka.presentation.tool.memory

import com.gromozeka.application.service.MemoryToolApplicationService
import com.gromozeka.application.service.memory.MEMORY_REMEMBER_TOOL_NAME
import com.gromozeka.application.service.memory.MemoryToolResultRenderer
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component

@Component
class MemoryRememberToolCallback(
    private val memoryToolApplicationService: MemoryToolApplicationService,
) : AiToolCallback {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class Input(
        val target: String = "previous_user_message",
        val target_message_id: String? = null,
        val text: String? = null,
        val user_consent_confirmed: Boolean = false,
        val mode: String? = null,
    )

    override val definition: AiToolDefinition = AiToolDefinition(
        name = MEMORY_REMEMBER_TOOL_NAME,
        description = "Persist memory-worthy information into typed memory. Use previous_user_message/message_id for normal conversation memory writes. The provided_text mode is only allowed when the user explicitly asks or consents to remember that exact arbitrary text; do not use it for assistant-generated summaries, guesses, rewritten content, or hidden compression unless the user approved that text.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "target": {
                  "type": "string",
                  "description": "Memory write target. Supports 'previous_user_message', 'message_id', and 'provided_text'."
                },
                "target_message_id": {
                  "type": "string",
                  "description": "Optional explicit message id in the current thread to use as the memory write target."
                },
                "text": {
                  "type": "string",
                  "description": "Exact arbitrary text to persist when target is 'provided_text'. This mode is allowed only with explicit user consent."
                },
                "user_consent_confirmed": {
                  "type": "boolean",
                  "description": "Must be true for target='provided_text'. Set it only when the user explicitly asked or agreed to remember the provided text."
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
        val conversationId = context?.getString("conversationId")
            ?: return@runBlocking MemoryToolResultRenderer.failureJsonString(
                "conversationId not found in ToolExecutionContext. Memory tools can only run inside a conversation turn."
            )

        val input = parseInput(toolInput)
        val providedText = input.text?.trim().orEmpty()
        if (input.target == "provided_text" || providedText.isNotBlank()) {
            if (providedText.isBlank()) {
                return@runBlocking MemoryToolResultRenderer.failureJsonString("target='provided_text' requires non-empty text.")
            }
            if (!input.user_consent_confirmed) {
                return@runBlocking MemoryToolResultRenderer.failureJsonString(
                    "target='provided_text' requires explicit user consent and user_consent_confirmed=true."
                )
            }
            return@runBlocking memoryToolApplicationService.rememberProvidedText(
                conversationIdValue = conversationId,
                text = providedText,
                mode = input.mode,
            )
        }

        if (input.target !in setOf("previous_user_message", "message_id")) {
            return@runBlocking MemoryToolResultRenderer.failureJsonString("Unsupported memory_remember target: ${input.target}")
        }
        if (input.target == "message_id" && input.target_message_id.isNullOrBlank()) {
            return@runBlocking MemoryToolResultRenderer.failureJsonString("target='message_id' requires target_message_id.")
        }

        memoryToolApplicationService.remember(
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
