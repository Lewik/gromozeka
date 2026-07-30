package com.gromozeka.application.service.memory

import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component

@Component
class MemoryForgetSourceToolCallback(
    private val memoryOperations: MemoryAsyncOperationApplicationService,
) : AiToolCallback {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class Input(
        val source_id: String,
        val user_consent_confirmed: Boolean = false,
    )

    override val definition: AiToolDefinition = AiToolDefinition(
        name = MEMORY_FORGET_SOURCE_TOOL_NAME,
        description = "Queue exact forgetting of one persisted memory source and return a run_id immediately. This also forgets document/section siblings in the same logical source and removes or prunes directly derived claims, notes, action items, episodes, and aliases. Use only when the user explicitly asks to forget the identified source and set user_consent_confirmed=true. Gromozeka delivers the final result automatically; external callers poll memory_run_status.",
        inputSchema = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["source_id", "user_consent_confirmed"],
              "properties": {
                "source_id": {
                  "type": "string",
                  "description": "Exact persisted memory source id to forget."
                },
                "user_consent_confirmed": {
                  "type": "boolean",
                  "description": "Must be true and may only be set after the user explicitly asks to forget this source."
                }
              }
            }
        """.trimIndent()
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        val input = json.decodeFromString<Input>(toolInput)
        if (!input.user_consent_confirmed) {
            return@runBlocking MemoryToolResultRenderer.failureJsonString(
                "Exact source forgetting requires explicit user consent and user_consent_confirmed=true."
            )
        }
        memoryOperations.forgetSource(
            conversationIdValue = context?.getString("conversationId"),
            namespace = context.requiredMemoryNamespace(),
            sourceIdValue = input.source_id,
            resultDelivery = context.memoryOperationResultDeliveryOrNull(),
        )
    }
}
