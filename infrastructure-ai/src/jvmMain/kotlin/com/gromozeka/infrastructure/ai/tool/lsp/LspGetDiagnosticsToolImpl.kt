package com.gromozeka.infrastructure.ai.tool.lsp

import com.gromozeka.domain.tool.lsp.LspGetDiagnosticsRequest
import com.gromozeka.domain.tool.lsp.LspGetDiagnosticsTool
import com.gromozeka.infrastructure.ai.service.lsp.LspClientService
import klog.KLoggers
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/**
 * Implementation of LSP get diagnostics tool.
 *
 * @see com.gromozeka.domain.tool.lsp.LspGetDiagnosticsTool Full domain specification
 */
@Service
class LspGetDiagnosticsToolImpl(
    private val lspClientService: LspClientService
) : LspGetDiagnosticsTool {

    private val log = KLoggers.logger(this)

    override fun execute(
        request: LspGetDiagnosticsRequest,
        context: ToolContext?
    ): Map<String, Any> {
        return try {
            val projectPath = context?.getContext()?.get("projectPath") as? String
                ?: return mapOf("error" to "Project path is required in tool context")

            log.info { "Getting diagnostics for ${request.language} file ${request.file_path}" }

            val filePath = resolveFilePath(request.file_path, projectPath)

            if (!filePath.toFile().exists()) {
                return mapOf("error" to "File not found: ${request.file_path}")
            }

            val client = lspClientService.getClient(request.language, projectPath)

            // Open file in LSP to trigger diagnostics
            val content = Files.readString(filePath)
            client.didOpenFile(filePath.toString(), content)

            // Wait a bit for diagnostics to be published
            Thread.sleep(500)

            val diagnostics = client.getDiagnostics(filePath.toString())

            val errors = diagnostics.count { it.severity == "error" }
            val warnings = diagnostics.count { it.severity == "warning" }
            val info = diagnostics.count { it.severity == "info" }
            val hints = diagnostics.count { it.severity == "hint" }

            val result = mutableMapOf<String, Any>(
                "diagnostics" to diagnostics.map { diag ->
                    mapOf(
                        "severity" to diag.severity,
                        "message" to diag.message,
                        "line" to diag.range.start.line,
                        "column" to diag.range.start.column,
                        "endLine" to diag.range.end.line,
                        "endColumn" to diag.range.end.column
                    ).let { map ->
                        diag.code?.let { map + ("code" to it) } ?: map
                    }.let { map ->
                        diag.source?.let { map + ("source" to it) } ?: map
                    }
                },
                "summary" to mapOf(
                    "errors" to errors,
                    "warnings" to warnings,
                    "info" to info,
                    "hints" to hints,
                    "total" to diagnostics.size
                ),
                "hasErrors" to (errors > 0)
            )

            if (diagnostics.isEmpty()) {
                result["message"] = "No issues found in ${request.file_path}"
            }

            // Close file after getting diagnostics
            client.didCloseFile(filePath.toString())

            result
        } catch (e: Exception) {
            log.error(e) { "LSP get diagnostics failed" }
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
