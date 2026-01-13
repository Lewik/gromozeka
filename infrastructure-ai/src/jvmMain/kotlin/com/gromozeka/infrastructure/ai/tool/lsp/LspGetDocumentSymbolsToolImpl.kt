package com.gromozeka.infrastructure.ai.tool.lsp

import com.gromozeka.domain.tool.lsp.LspGetDocumentSymbolsRequest
import com.gromozeka.domain.tool.lsp.LspGetDocumentSymbolsTool
import com.gromozeka.infrastructure.ai.service.lsp.LspClientService
import klog.KLoggers
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/**
 * Implementation of LSP get document symbols tool.
 *
 * @see com.gromozeka.domain.tool.lsp.LspGetDocumentSymbolsTool Full domain specification
 */
@Service
class LspGetDocumentSymbolsToolImpl(
    private val lspClientService: LspClientService
) : LspGetDocumentSymbolsTool {

    private val log = KLoggers.logger(this)

    override fun execute(
        request: LspGetDocumentSymbolsRequest,
        context: ToolContext?
    ): Map<String, Any> {
        return try {
            val projectPath = context?.getContext()?.get("projectPath") as? String
                ?: return mapOf("error" to "Project path is required in tool context")

            log.info { "Getting document symbols for ${request.language} file ${request.file_path}" }

            val filePath = resolveFilePath(request.file_path, projectPath)

            if (!filePath.toFile().exists()) {
                return mapOf("error" to "File not found: ${request.file_path}")
            }

            val client = lspClientService.getClient(request.language, projectPath)

            // Open file in LSP to ensure it's indexed
            val content = Files.readString(filePath)
            client.didOpenFile(filePath.toString(), content)

            // Get document symbols
            val symbols = client.getDocumentSymbols(filePath.toString())

            // Close file after getting symbols
            client.didCloseFile(filePath.toString())

            if (symbols.isEmpty()) {
                mapOf<String, Any>(
                    "symbols" to emptyList<Map<String, Any>>(),
                    "total" to 0,
                    "file_path" to request.file_path,
                    "message" to "No symbols found in ${request.file_path}"
                )
            } else {
                mapOf(
                    "symbols" to symbols.map { it.toMap() },
                    "total" to symbols.size,
                    "file_path" to request.file_path
                )
            }
        } catch (e: Exception) {
            log.error(e) { "LSP get document symbols failed" }
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

    private fun DocumentSymbol.toMap(): Map<String, Any> {
        val result = mutableMapOf<String, Any>(
            "name" to name,
            "kind" to symbolKindToString(kind),
            "range" to mapOf(
                "start" to mapOf("line" to range.start.line, "column" to range.start.character),
                "end" to mapOf("line" to range.end.line, "column" to range.end.character)
            )
        )

        // Add detail if present
        detail?.let { result["detail"] = it }

        // Add children recursively
        if (!children.isNullOrEmpty()) {
            result["children"] = children.map { it.toMap() }
        } else {
            result["children"] = emptyList<Map<String, Any>>()
        }

        return result
    }

    private fun symbolKindToString(kind: SymbolKind): String {
        return when (kind) {
            SymbolKind.File -> "File"
            SymbolKind.Module -> "Module"
            SymbolKind.Namespace -> "Namespace"
            SymbolKind.Package -> "Package"
            SymbolKind.Class -> "Class"
            SymbolKind.Method -> "Method"
            SymbolKind.Property -> "Property"
            SymbolKind.Field -> "Field"
            SymbolKind.Constructor -> "Constructor"
            SymbolKind.Enum -> "Enum"
            SymbolKind.Interface -> "Interface"
            SymbolKind.Function -> "Function"
            SymbolKind.Variable -> "Variable"
            SymbolKind.Constant -> "Constant"
            SymbolKind.String -> "String"
            SymbolKind.Number -> "Number"
            SymbolKind.Boolean -> "Boolean"
            SymbolKind.Array -> "Array"
            SymbolKind.Object -> "Object"
            SymbolKind.Key -> "Key"
            SymbolKind.Null -> "Null"
            SymbolKind.EnumMember -> "EnumMember"
            SymbolKind.Struct -> "Struct"
            SymbolKind.Event -> "Event"
            SymbolKind.Operator -> "Operator"
            SymbolKind.TypeParameter -> "TypeParameter"
            else -> "Unknown"
        }
    }
}
