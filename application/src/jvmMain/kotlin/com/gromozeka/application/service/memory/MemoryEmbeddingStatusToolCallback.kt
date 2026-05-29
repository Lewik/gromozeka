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
class MemoryEmbeddingStatusToolCallback(
    private val memoryToolApplicationService: MemoryToolApplicationService,
) : AiToolCallback {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class Input(
        val target_type: String? = null,
        val target: String? = null,
        val conversation_id: String? = null,
        val project_path: String? = null,
        val run_id: String? = null,
        val namespace: String? = null,
    )

    override val definition: AiToolDefinition = AiToolDefinition(
        name = MEMORY_EMBEDDING_STATUS_TOOL_NAME,
        description = "Report vector embedding coverage for one memory namespace under the currently configured embedding model. Counts embeddable memory items, expected embedding rows, existing rows, and missing rows. This does not modify memory.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
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
                  "description": "Conversation id whose project namespace should be inspected."
                },
                "project_path": {
                  "type": "string",
                  "description": "Absolute or relative project path whose project namespace should be inspected."
                },
                "run_id": {
                  "type": "string",
                  "description": "MemoryRun id. The run namespace is used as the inspection target."
                },
                "namespace": {
                  "type": "string",
                  "description": "Explicit memory namespace, currently usually project:<project-id>."
                }
              }
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
        memoryToolApplicationService.memoryEmbeddingStatus(
            conversationIdValue = input.conversation_id ?: context?.getString("conversationId")?.takeUnless { explicitTargetProvided },
            targetTypeValue = input.target_type,
            targetValue = input.target,
            projectPathValue = input.project_path,
            runIdValue = input.run_id,
            namespaceValue = input.namespace,
        )
    }

    private fun parseInput(toolInput: String): Input {
        if (toolInput.isBlank() || toolInput == "{}") {
            return Input()
        }
        return json.decodeFromString(toolInput)
    }
}
