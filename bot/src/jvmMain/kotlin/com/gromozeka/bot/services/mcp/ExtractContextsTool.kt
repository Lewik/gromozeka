package com.gromozeka.bot.services.mcp

import com.gromozeka.bot.services.ContextExtractionService
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service

@Service
class ExtractContextsTool(
    private val applicationContext: ApplicationContext,
) : GromozekaMcpTool {

    @Serializable
    data class Input(
        val tab_id: String,
    )

    override val definition = Tool(
        name = "extract_contexts",
        description = "Extract contexts from conversation in current or specified tab by creating background analysis session",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("tab_id", buildJsonObject {
                    put("type", "string")
                    put("description", "ID of the tab to extract contexts from")
                })
            },
            required = emptyList()
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val contextExtractionService = applicationContext.getBean(ContextExtractionService::class.java)
        val input = Json.decodeFromJsonElement<Input>(request.arguments)

        contextExtractionService.extractContextsFromTab(input.tab_id)

        val text = "Context extraction started in background tab. " +
                "The tab will analyze conversation and extract contexts automatically."
        return CallToolResult(
            content = listOf(TextContent(text)),
            isError = false
        )
    }
}