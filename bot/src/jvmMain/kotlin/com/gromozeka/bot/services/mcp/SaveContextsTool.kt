package com.gromozeka.bot.services.mcp

import com.gromozeka.bot.services.ContextFileService
import com.gromozeka.bot.ui.viewmodel.AppViewModel
import klog.KLoggers

import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service

@Service
class SaveContextsTool(
    private val applicationContext: ApplicationContext,
) : GromozekaMcpTool {
    private val log = KLoggers.logger(this)

    @Serializable
    data class Input(
        val xml_content: String,
    )

    override val definition = Tool(
        name = "save_contexts",
        description = """Save extracted contexts

Expected XML format:
<contexts>
  <context>
    <name>brief topic name</name>
    <files>
      <file path="path/to/file.kt">readfull</file>
      <file path="path/to/other.kt">
        <item>fun methodName</item>
        <item>class ClassName</item>
        <item>142:156</item>
      </file>
    </files>
    <links>
      <link>https://example.com/relevant-doc</link>
    </links>
    <content>key and sufficient information for this context</content>
  </context>
</contexts>

Required fields: name, content. Optional: files, links.""",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("xml_content", buildJsonObject {
                    put("type", "string")
                    put("description", "XML content with extracted contexts following the specified schema")
                })
            },
            required = listOf("xml_content")
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {

        val contextFileService = applicationContext.getBean(ContextFileService::class.java)
        val appViewModel = applicationContext.getBean(AppViewModel::class.java)
        val input = Json.decodeFromJsonElement<Input>(request.arguments)

        if (input.xml_content.isBlank()) {
            return CallToolResult(
                content = listOf(TextContent("Error: XML content cannot be empty")),
                isError = true
            )
        }

        val currentTab = appViewModel.currentTab.first() ?: return CallToolResult(
            content = listOf(TextContent("Error: No current tab found. Cannot determine project path.")),
            isError = true
        )

        val projectPath = currentTab.projectPath
        log.info("Processing XML content (${input.xml_content.length} chars) for project: $projectPath")

        contextFileService.parseXmlAndSaveContexts(input.xml_content, projectPath)

        return CallToolResult(
            content = listOf(TextContent("Contexts saved successfully")),
            isError = false
        )
    }
}