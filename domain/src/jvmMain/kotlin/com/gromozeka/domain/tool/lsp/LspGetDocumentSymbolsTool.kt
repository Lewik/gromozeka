package com.gromozeka.domain.tool.lsp

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for lsp_get_document_symbols tool.
 *
 * @property file_path Absolute or relative path to source file
 * @property language Programming language (kotlin, typescript, python, etc.)
 */
data class LspGetDocumentSymbolsRequest(
    val file_path: String,
    val language: String = "kotlin"
)

/**
 * Domain specification for LSP document symbols extraction.
 *
 * # MCP Tool Exposure
 *
 * **Tool name:** `lsp_get_document_symbols`
 *
 * **Exposed via:** Spring AI + MCP protocol
 *
 * # Purpose
 *
 * Extract hierarchical symbol structure from source file using Language Server Protocol.
 * Returns all top-level and nested symbols (classes, interfaces, functions, properties)
 * with their exact locations in the file.
 *
 * ## Core Features
 *
 * **Complete symbol hierarchy:**
 * - Top-level declarations (classes, interfaces, functions, objects)
 * - Nested symbols (methods, properties, inner classes)
 * - Preserves parent-child relationships
 * - Accurate line/column positions
 *
 * **Standard LSP format:**
 * - Uses LSP4J DocumentSymbol structure
 * - Compatible with any LSP server
 * - No custom parsing or transformation
 *
 * **Multi-language support:**
 * - Kotlin (kotlin-language-server)
 * - TypeScript/JavaScript (typescript-language-server)
 * - Python (pyright)
 * - Bash, JSON, YAML, and more
 *
 * **Fast and deterministic:**
 * - Pure LSP parsing (no LLM)
 * - Single request per file
 * - Cached LSP client per language/project
 *
 * # When to Use
 *
 * **Use lsp_get_document_symbols when:**
 * - Building code index or knowledge graph
 * - Generating file overview/documentation
 * - Analyzing code structure
 * - Finding all classes/functions in file
 * - Need exact symbol locations
 *
 * **Don't use when:**
 * - Need KDoc/JSDoc content → use lsp_get_hover on each symbol
 * - Need type information → use lsp_get_hover
 * - Need references → use lsp_find_references
 * - Need definition location → use lsp_find_definition
 *
 * # Parameters
 *
 * ## file_path: String (required)
 *
 * Path to source file (absolute or relative to project).
 *
 * **Examples:**
 * - `"domain/src/main/kotlin/Repository.kt"` - Relative path
 * - `"/Users/dev/project/src/Service.kt"` - Absolute path
 * - `"src/index.ts"` - TypeScript file
 *
 * **Validation:**
 * - File must exist
 * - Must be within project boundaries (if relative)
 *
 * ## language: String (optional, default: "kotlin")
 *
 * Programming language for LSP server selection.
 *
 * **Supported languages:**
 * - `"kotlin"` - kotlin-language-server
 * - `"typescript"` - typescript-language-server
 * - `"javascript"` - typescript-language-server
 * - `"python"` - pyright-langserver
 * - `"bash"` - bash-language-server
 * - `"json"` - vscode-json-languageserver
 * - `"yaml"` - yaml-language-server
 *
 * # Returns
 *
 * Returns `Map<String, Any>` with hierarchical symbol structure:
 *
 * ## Success Response
 *
 * ```json
 * {
 *   "symbols": [
 *     {
 *       "name": "ThreadRepository",
 *       "kind": "Interface",
 *       "range": {
 *         "start": {"line": 14, "column": 0},
 *         "end": {"line": 69, "column": 1}
 *       },
 *       "children": [
 *         {
 *           "name": "findById",
 *           "kind": "Method",
 *           "range": {
 *             "start": {"line": 20, "column": 4},
 *             "end": {"line": 20, "column": 50}
 *           },
 *           "children": []
 *         },
 *         {
 *           "name": "save",
 *           "kind": "Method",
 *           "range": {
 *             "start": {"line": 25, "column": 4},
 *             "end": {"line": 25, "column": 45}
 *           },
 *           "children": []
 *         }
 *       ]
 *     },
 *     {
 *       "name": "Thread",
 *       "kind": "Class",
 *       "range": {
 *         "start": {"line": 75, "column": 0},
 *         "end": {"line": 120, "column": 1}
 *       },
 *       "children": [
 *         {
 *           "name": "id",
 *           "kind": "Property",
 *           "range": {
 *             "start": {"line": 76, "column": 4},
 *             "end": {"line": 76, "column": 20}
 *           },
 *           "children": []
 *         },
 *         {
 *           "name": "messages",
 *           "kind": "Property",
 *           "range": {
 *             "start": {"line": 77, "column": 4},
 *             "end": {"line": 77, "column": 35}
 *           },
 *           "children": []
 *         }
 *       ]
 *     }
 *   ],
 *   "total": 2,
 *   "file_path": "domain/src/main/kotlin/ThreadRepository.kt"
 * }
 * ```
 *
 * **Symbol kinds:**
 * - `"File"` - Module/file symbol
 * - `"Module"` - Namespace/package
 * - `"Namespace"` - Namespace
 * - `"Package"` - Package
 * - `"Class"` - Class (includes data class, sealed class, object in Kotlin)
 * - `"Method"` - Method/function within class
 * - `"Property"` - Property/field
 * - `"Field"` - Field
 * - `"Constructor"` - Constructor
 * - `"Enum"` - Enum type
 * - `"Interface"` - Interface/trait
 * - `"Function"` - Top-level function
 * - `"Variable"` - Variable
 * - `"Constant"` - Constant
 * - `"String"` - String constant
 * - `"Number"` - Number constant
 * - `"Boolean"` - Boolean constant
 * - `"Array"` - Array
 * - `"Object"` - Object literal
 * - `"Key"` - Object key
 * - `"Null"` - Null value
 * - `"EnumMember"` - Enum constant
 * - `"Struct"` - Struct type
 * - `"Event"` - Event
 * - `"Operator"` - Operator
 * - `"TypeParameter"` - Generic type parameter
 *
 * ## Error Response
 *
 * ```json
 * {
 *   "error": "File not found: domain/src/Invalid.kt",
 *   "file_path": "domain/src/Invalid.kt"
 * }
 * ```
 *
 * # Error Cases
 *
 * | Error | Reason | Solution |
 * |-------|--------|----------|
 * | File not found | Path doesn't exist | Check file_path spelling |
 * | LSP not available | Language server not installed | Install LSP server for language |
 * | LSP timeout | Server not responding | Restart LSP client |
 * | Parse error | Syntax errors in file | Fix compilation errors |
 * | Project not in context | projectPath missing | Ensure tool context includes projectPath |
 *
 * # Usage Examples
 *
 * ## Example 1: Get Kotlin File Symbols
 *
 * ```json
 * {
 *   "tool": "lsp_get_document_symbols",
 *   "parameters": {
 *     "file_path": "domain/src/jvmMain/kotlin/com/gromozeka/domain/repository/ThreadRepository.kt",
 *     "language": "kotlin"
 *   }
 * }
 * ```
 *
 * **Result:** All interfaces, classes, functions in ThreadRepository.kt with exact positions
 *
 * ## Example 2: Index All Domain Files
 *
 * ```kotlin
 * val files = glob("domain/src/**/*.kt")
 * files.forEach { file ->
 *   val symbols = lsp_get_document_symbols(file_path = file, language = "kotlin")
 *   symbols["symbols"].forEach { symbol ->
 *     indexSymbol(symbol)
 *   }
 * }
 * ```
 *
 * **Result:** Complete domain layer indexed
 *
 * ## Example 3: Generate File Overview
 *
 * ```json
 * {
 *   "tool": "lsp_get_document_symbols",
 *   "parameters": {
 *     "file_path": "src/services/UserService.ts",
 *     "language": "typescript"
 *   }
 * }
 * ```
 *
 * **Result:** TypeScript service structure with all exported classes/functions
 *
 * ## Example 4: Find All Test Functions
 *
 * ```kotlin
 * val symbols = lsp_get_document_symbols(
 *   file_path = "src/test/kotlin/UserRepositoryTest.kt",
 *   language = "kotlin"
 * )
 *
 * val testFunctions = symbols["symbols"]
 *   .flatMap { it["children"] }
 *   .filter { it["name"].startsWith("test") }
 * ```
 *
 * **Result:** List of all test functions in file
 *
 * # Performance Characteristics
 *
 * **Single file:**
 * - Small file (<100 symbols): ~50-100ms
 * - Medium file (100-500 symbols): ~100-200ms
 * - Large file (500+ symbols): ~200-500ms
 *
 * **Batch processing (67 domain files):**
 * - Sequential: ~5-7 seconds (67 files × 75ms avg)
 * - Parallel (if implemented): ~1-2 seconds
 *
 * **Bottlenecks:**
 * - LSP server startup (one-time per language/project)
 * - File size and complexity
 * - Network latency (if LSP over network)
 *
 * **Caching:**
 * - LSP clients cached per language/project
 * - No symbol caching (always fresh data)
 * - LSP servers maintain their own internal caches
 *
 * # Symbol Hierarchy
 *
 * Symbols preserve exact nesting structure:
 *
 * ```kotlin
 * interface Repository {              // Top-level symbol
 *   fun findById(id: String): User?  // Child symbol (method)
 * }
 *
 * data class User(                    // Top-level symbol
 *   val id: String,                   // Child symbol (property)
 *   val name: String                  // Child symbol (property)
 * ) {
 *   fun validate(): Boolean           // Child symbol (method)
 * }
 * ```
 *
 * Returns:
 * ```json
 * {
 *   "symbols": [
 *     {
 *       "name": "Repository",
 *       "kind": "Interface",
 *       "children": [
 *         {"name": "findById", "kind": "Method"}
 *       ]
 *     },
 *     {
 *       "name": "User",
 *       "kind": "Class",
 *       "children": [
 *         {"name": "id", "kind": "Property"},
 *         {"name": "name", "kind": "Property"},
 *         {"name": "validate", "kind": "Method"}
 *       ]
 *     }
 *   ]
 * }
 * ```
 *
 * # Comparison with Serena
 *
 * **Serena `get_symbols_overview`:**
 * - Returns grouped format: `{"Struct": [...], "Interface": [...], "Class": [...]}`
 * - Kotlin-specific grouping (data class vs class vs object)
 * - No line numbers
 * - Custom format
 * - Requires MCP server
 *
 * **LSP `lsp_get_document_symbols`:**
 * - Returns hierarchical format: `{"symbols": [...]}`
 * - Standard LSP SymbolKind enum
 * - Includes exact positions (line/column)
 * - Standard LSP4J format
 * - Direct LSP client (no MCP)
 *
 * # Implementation Notes
 *
 * **LSP Request:**
 * Uses `textDocument/documentSymbol` request from LSP specification.
 *
 * **Symbol Structure:**
 * Each symbol contains:
 * - `name` - Symbol identifier
 * - `kind` - LSP SymbolKind enum value
 * - `range` - Full symbol range (including body)
 * - `selectionRange` - Name/identifier range (omitted in response)
 * - `children` - Nested symbols (empty array if none)
 *
 * **Language Server Requirements:**
 * - Server must support `textDocument/documentSymbol` capability
 * - Most modern LSP servers support this
 * - Falls back to empty list if not supported
 *
 * # Related Tools
 *
 * - **lsp_get_hover** - Get documentation/type info for specific symbol
 * - **lsp_find_definition** - Find where symbol is defined
 * - **lsp_find_references** - Find all usages of symbol
 * - **lsp_get_diagnostics** - Get compilation errors/warnings
 * - **index_domain_to_graph** - Index symbols into knowledge graph (uses this tool)
 *
 * # Infrastructure Implementation
 *
 * See infrastructure-ai module for Spring AI integration:
 * @see com.gromozeka.infrastructure.ai.tool.lsp.LspGetDocumentSymbolsToolImpl
 */
interface LspGetDocumentSymbolsTool : Tool<LspGetDocumentSymbolsRequest, Map<String, Any>> {

    override val name: String
        get() = "lsp_get_document_symbols"

    override val description: String
        get() = """
            Extract hierarchical symbol structure from source file using Language Server Protocol.

            Returns all symbols (classes, interfaces, functions, properties) with their exact
            locations and nesting structure. Uses standard LSP documentSymbol request.

            Features:
            - Complete symbol hierarchy (preserves parent-child relationships)
            - Exact line/column positions for each symbol
            - Standard LSP SymbolKind enum
            - Multi-language support (Kotlin, TypeScript, Python, etc.)
            - Fast and deterministic (pure LSP, no LLM)

            Use cases:
            - Build code index or knowledge graph
            - Generate file overview/documentation
            - Analyze code structure
            - Find all classes/functions in file
        """.trimIndent()

    override val requestType: Class<LspGetDocumentSymbolsRequest>
        get() = LspGetDocumentSymbolsRequest::class.java

    override fun execute(request: LspGetDocumentSymbolsRequest, context: ToolContext?): Map<String, Any>
}
