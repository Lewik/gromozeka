package com.gromozeka.presentation.config

import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.infrastructure.ai.mcp.tools.GromozekaMcpTool
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import jakarta.annotation.PostConstruct
import klog.KLoggers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
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
                inputSchema = if (mcpTool.definition.inputSchema != null) {
                    Json.encodeToString(
                        io.modelcontextprotocol.kotlin.sdk.Tool.Input.serializer(),
                        mcpTool.definition.inputSchema!!
                    )
                } else {
                    """{"type":"object","properties":{}}"""
                }
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

                        val request = CallToolRequest(
                            name = mcpTool.definition.name,
                            arguments = argumentsObject
                        )

                        val result = mcpTool.execute(request)

                        val resultText = result.content.joinToString("\n") { content ->
                            when (content) {
                                is io.modelcontextprotocol.kotlin.sdk.TextContent -> content.text ?: ""
                                is io.modelcontextprotocol.kotlin.sdk.ImageContent -> "[Image: ${content.mimeType ?: "image"}]"
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

    private fun parseToolInput(toolInput: String, toolName: String): JsonElement {
        return if (toolInput.isBlank() || toolInput == "{}") {
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
}
