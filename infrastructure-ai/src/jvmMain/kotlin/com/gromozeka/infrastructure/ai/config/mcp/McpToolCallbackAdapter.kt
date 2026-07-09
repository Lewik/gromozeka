package com.gromozeka.infrastructure.ai.config.mcp

import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ToolExecutionContext
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class McpToolCallbackAdapter(
    private val clientWrapper: McpWrapperInterface,
    private val tool: Tool,
    private val coroutineScope: CoroutineScope
) : AiToolCallback {

    private val log = KLoggers.logger {}
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val definition: AiToolDefinition = AiToolDefinition(
        name = tool.name,
        description = tool.description ?: "",
        inputSchema = Json.encodeToString(ToolSchema.serializer(), tool.inputSchema)
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String {
        return runBlocking {
            try {
                log.debug { "Calling MCP tool: ${tool.name} with input: $toolInput" }

                val arguments = if (toolInput.isBlank() || toolInput == "{}") {
                    emptyMap()
                } else {
                    try {
                        val element = json.parseToJsonElement(toolInput)
                        if (element is JsonObject) {
                            convertJsonObjectToMap(element)
                        } else {
                            emptyMap()
                        }
                    } catch (e: Exception) {
                        log.warn(e) { "Failed to parse tool input as JSON, using empty map" }
                        emptyMap()
                    }
                }

                val result = clientWrapper.callTool(tool.name, arguments)

                log.debug { "MCP tool ${tool.name} result: $result" }
                result
            } catch (e: Exception) {
                val errorMsg = "Error executing MCP tool ${tool.name}: ${e.message}"
                log.error(e) { errorMsg }
                errorMsg
            }
        }
    }

    private fun convertJsonObjectToMap(jsonObject: JsonObject): Map<String, Any> {
        return jsonObject.mapValues { (_, value) ->
            convertJsonElement(value)
        }
    }

    private fun convertJsonElement(element: JsonElement): Any {
        return when (element) {
            is JsonPrimitive -> {
                when {
                    element.isString -> element.content
                    element.booleanOrNull != null -> element.boolean
                    element.intOrNull != null -> element.int
                    element.longOrNull != null -> element.long
                    element.doubleOrNull != null -> element.double
                    else -> element.content
                }
            }
            is JsonObject -> convertJsonObjectToMap(element)
            is JsonArray -> element.map { convertJsonElement(it) }
            else -> element.toString()
        }
    }
}
