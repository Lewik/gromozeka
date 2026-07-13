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
class MemoryRebuildEmbeddingsToolCallback(
    private val memoryToolApplicationService: MemoryToolApplicationService,
) : AiToolCallback {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class Input(
        val mode: String? = null,
    )

    override val definition: AiToolDefinition = AiToolDefinition(
        name = MEMORY_REBUILD_EMBEDDINGS_TOOL_NAME,
        description = "Rebuild memory vector embeddings in the configured namespace. mode=full first generates a complete fresh embedding set and then atomically replaces all existing embeddings only if generation succeeded. mode=missing inserts only currently absent embeddings and never deletes or rewrites existing rows. Returns a run_id immediately; use memory_run_status or memory_queue_status to observe progress.",
        inputSchema = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "mode": {
                  "type": "string",
                  "enum": ["full", "missing"],
                  "description": "full resets and rebuilds the namespace after successful generation; missing fills only absent embeddings. Defaults to full."
                }
              }
            }
        """.trimIndent()
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        val input = parseInput(toolInput)
        memoryToolApplicationService.runMaintenance(
            actionValue = MemoryMaintenanceAction.REBUILD_EMBEDDINGS.toolName,
            conversationIdValue = context?.getString("conversationId"),
            embeddingRebuildModeValue = input.mode,
        )
    }

    private fun parseInput(toolInput: String): Input {
        if (toolInput.isBlank() || toolInput == "{}") {
            return Input()
        }
        return json.decodeFromString(toolInput)
    }
}
