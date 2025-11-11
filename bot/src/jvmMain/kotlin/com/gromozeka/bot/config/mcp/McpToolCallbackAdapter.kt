package com.gromozeka.bot.config.mcp

import io.modelcontextprotocol.kotlin.sdk.Tool
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.ToolCallback

class McpToolCallbackAdapter(
    private val clientWrapper: McpWrapperInterface,
    private val tool: Tool,
    private val coroutineScope: CoroutineScope
) : ToolCallback {

    private val log = KLoggers.logger {}
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun call(toolInput: String): String {
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

    override fun getToolDefinition(): ToolDefinition {
        val inputSchemaJson = if (tool.inputSchema != null) {
            Json.encodeToString(io.modelcontextprotocol.kotlin.sdk.Tool.Input.serializer(), tool.inputSchema!!)
        } else {
            """{"type":"object","properties":{}}"""
        }

        return object : ToolDefinition {
            override fun name(): String = tool.name
            override fun description(): String = tool.description ?: ""
            override fun inputSchema(): String = inputSchemaJson
        }
    }
}
