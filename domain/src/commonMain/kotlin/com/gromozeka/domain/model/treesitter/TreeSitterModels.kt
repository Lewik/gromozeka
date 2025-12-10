package com.gromozeka.domain.model.treesitter

import kotlinx.serialization.Serializable

/**
 * Registered project information for Tree-sitter analysis
 */
@Serializable
data class ProjectInfo(
    val name: String,
    val rootPath: String,
    val description: String? = null,
    val languages: Set<String> = emptySet(),
    val lastScanTime: String? = null
)

/**
 * File analysis result
 */
@Serializable
data class FileAnalysis(
    val path: String,
    val language: String,
    val symbols: Map<String, List<SymbolInfo>> = emptyMap(),
    val dependencies: List<String> = emptyList(),
    val complexity: CodeComplexity? = null
)

/**
 * Symbol information (function, class, variable, etc.)
 */
@Serializable
data class SymbolInfo(
    val name: String,
    val type: String, // "function", "class", "variable", "import", etc.
    val startLine: Int,
    val endLine: Int,
    val startColumn: Int = 0,
    val endColumn: Int = 0,
    val signature: String? = null,
    val docstring: String? = null
)

/**
 * Code complexity metrics
 */
@Serializable
data class CodeComplexity(
    val lineCount: Int,
    val codeLines: Int,
    val commentLines: Int,
    val blankLines: Int,
    val functionCount: Int,
    val classCount: Int,
    val cyclomaticComplexity: Int = 0,
    val avgFunctionLines: Int = 0
) {
    val commentRatio: Double
        get() = if (codeLines > 0) commentLines.toDouble() / codeLines.toDouble() else 0.0
}

/**
 * Search result for text/query searches
 */
@Serializable
data class SearchResult(
    val file: String,
    val line: Int,
    val column: Int = 0,
    val text: String,
    val context: List<ContextLine> = emptyList(),
    val capture: String? = null // For tree-sitter query captures
)

/**
 * Context line for search results
 */
@Serializable
data class ContextLine(
    val line: Int,
    val text: String,
    val isMatch: Boolean = false
)

/**
 * Tree-sitter AST node representation
 */
@Serializable
data class AstNode(
    val type: String,
    val startLine: Int,
    val endLine: Int,
    val startColumn: Int,
    val endColumn: Int,
    val text: String? = null,
    val children: List<AstNode> = emptyList(),
    val fieldName: String? = null
)

/**
 * File AST response
 */
@Serializable
data class FileAst(
    val file: String,
    val language: String,
    val tree: AstNode
)
