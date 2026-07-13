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
class MemoryMaintenanceToolCallback(
    private val memoryToolApplicationService: MemoryToolApplicationService,
) : AiToolCallback {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class Input(
        val action: String = "",
        val embedding_mode: String? = null,
    )

    override val definition: AiToolDefinition = AiToolDefinition(
        name = MEMORY_MAINTENANCE_TOOL_NAME,
        description = "Schedule one explicit maintenance pass over typed memory in the global namespace and return immediately with a run_id. Actions: consolidate promotes mature notes into durable claims/actionItems/episodes; repair fixes suspicious duplicate or conflicting memory; maintain_entities normalizes entity aliases/merges/summaries; apply_retention cools, archives, or hides stale memory; rebuild_embeddings rebuilds vector indexes. Use memory_run_status with the returned run_id or memory_queue_status to observe progress.",
        inputSchema = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "action": {
                  "type": "string",
                  "description": "Maintenance action to queue: consolidate, repair, maintain_entities, apply_retention, or rebuild_embeddings."
                },
                "embedding_mode": {
                  "type": "string",
                  "enum": ["full", "missing"],
                  "description": "Only for action=rebuild_embeddings. full replaces all namespace embeddings after successful generation; missing inserts only absent embeddings."
                }
              },
              "required": ["action"]
            }
        """.trimIndent()
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        val input = parseInput(toolInput)
        memoryToolApplicationService.runMaintenance(
            actionValue = input.action,
            conversationIdValue = context?.getString("conversationId"),
            embeddingRebuildModeValue = input.embedding_mode,
        )
    }

    private fun parseInput(toolInput: String): Input {
        if (toolInput.isBlank() || toolInput == "{}") {
            return Input()
        }
        return json.decodeFromString(toolInput)
    }
}
