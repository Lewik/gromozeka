package com.gromozeka.infrastructure.ai.tool.lsp

import com.gromozeka.domain.tool.lsp.LspFindDefinitionRequest
import com.gromozeka.domain.tool.lsp.LspFindDefinitionTool
import com.gromozeka.infrastructure.ai.service.lsp.LspClientService
import klog.KLoggers
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredWorkspaceRootPath
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Implementation of LSP find definition tool.
 *
 * @see com.gromozeka.domain.tool.lsp.LspFindDefinitionTool Full domain specification
 */
@Service
class LspFindDefinitionToolImpl(
    private val lspClientService: LspClientService
) : LspFindDefinitionTool {

    private val log = KLoggers.logger(this)

    override fun execute(
        request: LspFindDefinitionRequest,
        context: ToolExecutionContext?
    ): Map<String, Any> {
        val workspaceRootPath = context.requiredWorkspaceRootPath()
        return try {
            log.info { "Finding definition for ${request.language} at ${request.file_path}:${request.line}:${request.column}" }

            val filePath = resolveFilePath(request.file_path, workspaceRootPath)

            if (!filePath.toFile().exists()) {
                return mapOf("error" to "File not found: ${request.file_path}")
            }

            val client = lspClientService.getClient(request.language, workspaceRootPath)

            val locations = client.findDefinition(
                filePath.toString(),
                request.line,
                request.column
            )

            if (locations.isEmpty()) {
                mapOf(
                    "locations" to emptyList<Map<String, Any>>(),
                    "message" to "No definition found at ${request.file_path}:${request.line}:${request.column}"
                )
            } else {
                mapOf(
                    "locations" to locations.map { loc ->
                        mapOf(
                            "uri" to loc.uri,
                            "line" to loc.range.start.line,
                            "column" to loc.range.start.column,
                            "endLine" to loc.range.end.line,
                            "endColumn" to loc.range.end.column
                        )
                    }
                )
            }
        } catch (e: Exception) {
            log.error(e) { "LSP find definition failed" }
            mapOf("error" to "LSP error: ${e.message}")
        }
    }

    private fun resolveFilePath(filePath: String, workspaceRootPath: String): Path {
        val path = Path.of(filePath)
        return if (path.isAbsolute) {
            path
        } else {
            Path.of(workspaceRootPath).resolve(filePath)
        }
    }
}
