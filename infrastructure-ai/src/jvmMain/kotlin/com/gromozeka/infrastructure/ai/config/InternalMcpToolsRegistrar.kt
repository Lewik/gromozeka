package com.gromozeka.infrastructure.ai.config

import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.infrastructure.ai.mcp.tools.GromozekaMcpTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import jakarta.annotation.PostConstruct
import klog.KLoggers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component

@Component
class InternalMcpToolsRegistrar(
    private val mcpTools: List<GromozekaMcpTool>,
    private val applicationContext: ApplicationContext
) {
    private val log = KLoggers.logger(this)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @PostConstruct
    fun registerTools() {
        log.info { "Registering ${mcpTools.size} internal MCP tools as AiToolCallback beans" }

        val beanFactory = (applicationContext as ConfigurableApplicationContext).beanFactory

        mcpTools.forEach { mcpTool ->
            val toolCallback = createToolCallback(mcpTool)
            val beanName = "internalMcpTool_${mcpTool.definition.name}AiToolCallback"

            beanFactory.registerSingleton(beanName, toolCallback)
            log.info { "Registered internal MCP tool: ${toolCallback.definition.name}" }
        }
    }

    private fun createToolCallback(mcpTool: GromozekaMcpTool): AiToolCallback {
        val registrar = this
        return object : AiToolCallback {
            override val definition: AiToolDefinition = AiToolDefinition(
                name = mcpTool.definition.name,
                description = mcpTool.definition.description ?: "",
                inputSchema = Json.encodeToString(ToolSchema.serializer(), mcpTool.definition.inputSchema)
            )

            override fun call(toolInput: String, context: com.gromozeka.domain.tool.ToolExecutionContext?): String {
                return runBlocking {
                    try {
                        registrar.log.debug { "Calling internal MCP tool: ${mcpTool.definition.name} with input: $toolInput" }

                        val arguments = registrar.parseToolInput(toolInput, mcpTool.definition.name)

                        val argumentsObject = if (arguments is JsonObject) {
                            arguments
                        } else {
                            buildJsonObject {}
                        }

                        val request = CallToolRequest(CallToolRequestParams(
                            name = mcpTool.definition.name,
                            arguments = argumentsObject
                        ))

                        val result = mcpTool.execute(request)

                        val resultText = result.content.joinToString("\n") { content ->
                            when (content) {
                                is TextContent -> content.text
                                is ImageContent -> "[Image: ${content.mimeType}]"
                                else -> content.toString()
                            }
                        }

                        registrar.log.debug { "Internal MCP tool ${mcpTool.definition.name} result: $resultText" }
                        resultText
                    } catch (e: Exception) {
                        val errorMsg = "Error executing internal MCP tool ${mcpTool.definition.name}: ${e.message}"
                        registrar.log.error(e) { errorMsg }
                        errorMsg
                    }
                }
            }
        }
    }

    private fun parseToolInput(toolInput: String, toolName: String) =
        if (toolInput.isBlank() || toolInput == "{}") {
            buildJsonObject {}
        } else {
            try {
                json.parseToJsonElement(toolInput)
            } catch (e: Exception) {
                log.warn(e) { "Failed to parse tool input as JSON for $toolName, using empty object" }
                buildJsonObject {}
            }
        }
}
