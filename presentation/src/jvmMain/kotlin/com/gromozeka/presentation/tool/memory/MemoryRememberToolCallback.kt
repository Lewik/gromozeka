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
        val mode: String? = null,
    )

    override val definition: AiToolDefinition = AiToolDefinition(
        name = MEMORY_REMEMBER_TOOL_NAME,
        description = "Persist memory-worthy information from the current conversation turn into typed memory. Use this when the user explicitly asks to remember, update, or forget something, or when you intentionally want a durable memory write now.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "target": {
                  "type": "string",
                  "description": "Message selection mode. Currently supports 'previous_user_message'."
                },
                "target_message_id": {
                  "type": "string",
                  "description": "Optional explicit message id in the current thread to use as the memory write target."
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
