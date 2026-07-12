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
        val target_type: String? = null,
        val target: String? = null,
        val conversation_id: String? = null,
        val project_path: String? = null,
        val run_id: String? = null,
        val namespace: String? = null,
        val embedding_mode: String? = null,
    )

    override val definition: AiToolDefinition = AiToolDefinition(
        name = MEMORY_MAINTENANCE_TOOL_NAME,
        description = "Schedule one explicit maintenance pass over typed memory and return immediately with a run_id. Actions: consolidate promotes mature notes into durable claims/actionItems/episodes; repair fixes suspicious duplicate or conflicting memory; maintain_entities normalizes entity aliases/merges/summaries; apply_retention cools, archives, or hides stale memory; rebuild_embeddings rebuilds vector indexes for the target namespace. The caller should pass a target such as project_path, conversation_id, run_id, or namespace. If called inside a conversation with no target, the current conversation is used. Use memory_run_status with the returned run_id or memory_queue_status to observe progress.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "description": "Maintenance action to queue: consolidate, repair, maintain_entities, apply_retention, or rebuild_embeddings."
                },
                "target_type": {
                  "type": "string",
                  "description": "Optional generic target kind: project_path, conversation_id, run_id, or namespace."
                },
                "target": {
                  "type": "string",
                  "description": "Target value matching target_type."
                },
                "conversation_id": {
                  "type": "string",
                  "description": "Conversation id whose project namespace should be maintained."
                },
                "project_path": {
                  "type": "string",
                  "description": "Absolute or relative project path whose project namespace should be maintained."
                },
                "run_id": {
                  "type": "string",
                  "description": "MemoryRun id. The run namespace is used as the maintenance target."
                },
                "namespace": {
                  "type": "string",
                  "description": "Explicit readable memory namespace such as global, user:lewik, work:hebrew, or project:<project-id>."
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
        val explicitTargetProvided = listOf(
            input.target_type,
            input.target,
            input.conversation_id,
            input.project_path,
            input.run_id,
            input.namespace,
        ).any { !it.isNullOrBlank() }
        memoryToolApplicationService.runMaintenance(
            actionValue = input.action,
            conversationIdValue = input.conversation_id ?: context?.getString("conversationId")?.takeUnless { explicitTargetProvided },
            targetTypeValue = input.target_type,
            targetValue = input.target,
            projectPathValue = input.project_path,
            runIdValue = input.run_id,
            namespaceValue = input.namespace,
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
