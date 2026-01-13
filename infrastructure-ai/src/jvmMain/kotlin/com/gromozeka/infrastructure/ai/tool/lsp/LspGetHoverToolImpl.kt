package com.gromozeka.infrastructure.ai.tool.lsp

import com.gromozeka.domain.tool.lsp.LspGetHoverRequest
import com.gromozeka.domain.tool.lsp.LspGetHoverTool
import com.gromozeka.infrastructure.ai.service.lsp.LspClientService
import klog.KLoggers
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Implementation of LSP get hover tool.
 *
 * @see com.gromozeka.domain.tool.lsp.LspGetHoverTool Full domain specification
 */
@Service
class LspGetHoverToolImpl(
    private val lspClientService: LspClientService
) : LspGetHoverTool {

    private val log = KLoggers.logger(this)

    override fun execute(
        request: LspGetHoverRequest,
        context: ToolContext?
    ): Map<String, Any> {
        return try {
            val projectPath = context?.getContext()?.get("projectPath") as? String
                ?: return mapOf("error" to "Project path is required in tool context")

            log.info { "Getting hover info for ${request.language} at ${request.file_path}:${request.line}:${request.column}" }

            val filePath = resolveFilePath(request.file_path, projectPath)

            if (!filePath.toFile().exists()) {
                return mapOf("error" to "File not found: ${request.file_path}")
            }

            val client = lspClientService.getClient(request.language, projectPath)

            val hoverInfo = client.getHover(
                filePath.toString(),
                request.line,
                request.column
            )

            if (hoverInfo == null) {
                mapOf<String, Any>(
                    "message" to "No hover information available at this location"
                )
            } else {
                val result = mutableMapOf<String, Any>(
                    "content" to hoverInfo.content
                )

                hoverInfo.range?.let { range ->
                    result["range"] = mapOf(
                        "start" to mapOf("line" to range.start.line, "column" to range.start.column),
                        "end" to mapOf("line" to range.end.line, "column" to range.end.column)
                    )
                }

                // Check if content contains deprecation markers
                if (hoverInfo.content.contains("@deprecated", ignoreCase = true) ||
                    hoverInfo.content.contains("deprecated", ignoreCase = true)) {
                    result["deprecated"] = true
                }

                result
            }
        } catch (e: Exception) {
            log.error(e) { "LSP get hover failed" }
            mapOf("error" to "LSP error: ${e.message}")
        }
    }

    private fun resolveFilePath(filePath: String, projectPath: String): Path {
        val path = Path.of(filePath)
        return if (path.isAbsolute) {
            path
        } else {
            Path.of(projectPath).resolve(filePath)
        }
    }
}
