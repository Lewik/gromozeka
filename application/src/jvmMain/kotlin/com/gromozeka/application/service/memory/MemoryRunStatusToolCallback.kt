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
class MemoryRunStatusToolCallback(
    private val memoryToolApplicationService: MemoryToolApplicationService,
) : AiToolCallback {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class Input(
        val run_id: String = "",
        val include_children: Boolean = true,
        val max_depth: Int = 4,
    )

    override val definition: AiToolDefinition = AiToolDefinition(
        name = MEMORY_RUN_STATUS_TOOL_NAME,
        description = "Read persisted status for one memory run. " +
            "Use this to inspect document ingest progress, child write runs, timings, errors, and final state by run_id.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "run_id": {
                  "type": "string",
                  "description": "Exact MemoryRun id returned by memory_remember or seen in logs."
                },
                "include_children": {
                  "type": "boolean",
                  "description": "Whether to include child runs linked by parentRunId or childRunIds. Defaults to true."
                },
                "max_depth": {
                  "type": "integer",
                  "description": "Maximum child-run tree depth to include. Defaults to 4."
                }
              },
              "required": ["run_id"]
            }
        """.trimIndent()
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        val input = parseInput(toolInput)
        memoryToolApplicationService.memoryRunStatus(
            runIdValue = input.run_id,
            includeChildren = input.include_children,
            maxDepth = input.max_depth,
        )
    }

    private fun parseInput(toolInput: String): Input {
        if (toolInput.isBlank() || toolInput == "{}") {
            return Input()
        }
        return json.decodeFromString(toolInput)
    }
}
