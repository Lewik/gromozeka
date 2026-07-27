package com.gromozeka.infrastructure.ai.config.mcp

import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.model.mcp.McpToolSnapshot
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_MEMORY_RESULT_DELIVERY
import com.gromozeka.domain.tool.TOOL_CONTEXT_TARGET_MESSAGE_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_THREAD_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_TOOL_NAME
import com.gromozeka.domain.tool.ToolExecutionContext
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class McpToolCallbackAdapter(
    serverId: McpServerId,
    private val client: McpConnectedClient,
    private val tool: McpToolSnapshot,
    private val forwardGrzConversationContext: Boolean = false,
) : AiToolCallback {

    private val log = KLoggers.logger {}
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val definition: AiToolDefinition = tool.toAiToolDefinition(serverId)

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        try {
            log.debug { "Calling MCP tool: ${tool.remoteName} with input: $toolInput" }
            val arguments = parseArguments(toolInput)
                .withGrzConversationContext(
                    context = context,
                    enabled = forwardGrzConversationContext,
                )
            client.callTool(tool.remoteName, arguments).also { result ->
                log.debug { "MCP tool ${tool.remoteName} result: $result" }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = "Error executing MCP tool ${tool.remoteName}: ${error.message}"
            log.error(error) { message }
            throw IllegalStateException(message, error)
        }
    }

    private fun parseArguments(toolInput: String): Map<String, Any?> {
        if (toolInput.isBlank()) {
            return emptyMap()
        }
        val element = json.parseToJsonElement(toolInput)
        require(element is JsonObject) {
            "MCP tool input must be a JSON object"
        }
        return convertJsonObjectToMap(element)
    }

    private fun convertJsonObjectToMap(jsonObject: JsonObject): Map<String, Any?> {
        return jsonObject.mapValues { (_, value) ->
            convertJsonElement(value)
        }
    }

    private fun convertJsonElement(element: JsonElement): Any? {
        return when (element) {
            JsonNull -> null
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

internal fun Map<String, Any?>.withGrzConversationContext(
    context: ToolExecutionContext?,
    enabled: Boolean,
): Map<String, Any?> {
    if (!enabled || context == null) {
        return this
    }

    val forwardedContext = listOf(
        TOOL_CONTEXT_CONVERSATION_ID,
        TOOL_CONTEXT_THREAD_ID,
        TOOL_CONTEXT_TARGET_MESSAGE_ID,
        TOOL_CONTEXT_AGENT_DEFINITION_ID,
        TOOL_CONTEXT_TOOL_NAME,
        TOOL_CONTEXT_MEMORY_RESULT_DELIVERY,
    ).mapNotNull { key ->
        context.getString(key)?.takeIf { it.isNotBlank() }?.let { key to it }
    }.toMap()

    return if (forwardedContext.isEmpty()) {
        this
    } else {
        this + ("_context" to forwardedContext)
    }
}
