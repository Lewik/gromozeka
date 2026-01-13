package com.gromozeka.infrastructure.ai.tool.lsp

import com.gromozeka.domain.tool.lsp.LspFindReferencesRequest
import com.gromozeka.domain.tool.lsp.LspFindReferencesTool
import com.gromozeka.infrastructure.ai.service.lsp.LspClientService
import klog.KLoggers
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Implementation of LSP find references tool.
 *
 * @see com.gromozeka.domain.tool.lsp.LspFindReferencesTool Full domain specification
 */
@Service
class LspFindReferencesToolImpl(
    private val lspClientService: LspClientService
) : LspFindReferencesTool {

    private val log = KLoggers.logger(this)

    override fun execute(
        request: LspFindReferencesRequest,
        context: ToolContext?
    ): Map<String, Any> {
        return try {
            val projectPath = context?.getContext()?.get("projectPath") as? String
                ?: return mapOf("error" to "Project path is required in tool context")

            log.info {
                "Finding references for ${request.language} at ${request.file_path}:${request.line}:${request.column} " +
                "(includeDeclaration=${request.include_declaration})"
            }

            val filePath = resolveFilePath(request.file_path, projectPath)

            if (!filePath.toFile().exists()) {
                return mapOf("error" to "File not found: ${request.file_path}")
            }

            val client = lspClientService.getClient(request.language, projectPath)

            val locations = client.findReferences(
                filePath.toString(),
                request.line,
                request.column,
                request.include_declaration
            )

            val declarationCount = locations.count { it.uri.contains(request.file_path) }
            val usageCount = locations.size - declarationCount

            when {
                locations.isEmpty() -> mapOf(
                    "references" to emptyList<Map<String, Any>>(),
                    "total" to 0,
                    "message" to "No references found for symbol at ${request.file_path}:${request.line}:${request.column}",
                    "suggestion" to "This might be dead code - consider removing"
                )

                locations.size == 1 && request.include_declaration -> mapOf(
                    "references" to locations.map { it.toMap() },
                    "total" to 1,
                    "message" to "Symbol declared but never used",
                    "suggestion" to "Consider removing if not part of public API"
                )

                else -> mapOf(
                    "references" to locations.map { it.toMap() },
                    "total" to locations.size,
                    "summary" to "Found ${locations.size} references ($declarationCount declaration, $usageCount usages)"
                )
            }
        } catch (e: Exception) {
            log.error(e) { "LSP find references failed" }
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

    private fun com.gromozeka.infrastructure.ai.service.lsp.LocationInfo.toMap() = mapOf(
        "uri" to uri,
        "line" to range.start.line,
        "column" to range.start.column,
        "endLine" to range.end.line,
        "endColumn" to range.end.column
    )
}
