package com.gromozeka.domain.tool.lsp

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for lsp_find_references tool.
 *
 * @property file_path Path to the file (absolute or relative to project root)
 * @property line Zero-based line number where the symbol is located
 * @property column Zero-based column number where the symbol is located
 * @property include_declaration Whether to include the declaration in results (default: true)
 * @property language Language identifier for LSP server selection (kotlin, typescript, python, etc.)
 */
data class LspFindReferencesRequest(
    val file_path: String,
    val line: Int,
    val column: Int,
    val include_declaration: Boolean = true,
    val language: String = "kotlin"
)

/**
 * Domain specification for LSP find references tool.
 *
 * # MCP Tool Exposure
 *
 * **Tool name:** `lsp_find_references`
 *
 * **Exposed via:** Spring AI + MCP protocol
 *
 * # Purpose
 *
 * Find all usages (references) of a symbol across the entire project using Language Server Protocol.
 * Provides semantic "Find All References" functionality similar to modern IDEs.
 *
 * ## Core Features
 *
 * **Comprehensive search:**
 * - Finds all usages of functions, classes, variables
 * - Works across all project files
 * - Includes references in comments (language-dependent)
 * - Optionally includes the declaration itself
 *
 * **Semantic understanding:**
 * - Distinguishes between same-named symbols in different scopes
 * - Understands imports and namespaces
 * - Tracks type usage vs instance usage
 * - Handles shadowing and scoping correctly
 *
 * **Multi-language support:**
 * - Kotlin, TypeScript, Python, Bash, and more
 * - Language-specific reference semantics
 * - Works with language-specific features (generics, decorators, etc.)
 *
 * # When to Use
 *
 * **Use lsp_find_references when:**
 * - Understanding impact of changing a function/class
 * - Finding all places where an API is used
 * - Refactoring: need to update all usages
 * - Analyzing code dependencies
 * - Finding dead code (0 references)
 * - Understanding how a library is consumed
 *
 * **Don't use when:**
 * - Need to navigate to definition → use `lsp_find_definition`
 * - Need symbol documentation → use `lsp_get_hover`
 * - Searching for text patterns → use `grz_execute_command` with grep
 * - Need type hierarchy → consider `lsp_find_definition` + manual analysis
 *
 * # Parameters
 *
 * ## file_path: String (required)
 *
 * Path to file containing the symbol whose references you want to find.
 *
 * **Examples:**
 * - `"src/main/kotlin/UserService.kt"` - Service file
 * - `"src/types/User.ts"` - Type definition file
 *
 * ## line: Int (required)
 *
 * Zero-based line number where the symbol is located.
 *
 * **Tips:**
 * - Point to the declaration or any usage
 * - LSP will resolve to same symbol regardless
 *
 * ## column: Int (required)
 *
 * Zero-based column number where the symbol is located.
 *
 * **Tips:**
 * - Point anywhere within the symbol name
 * - Doesn't need to be exact start position
 *
 * ## include_declaration: Boolean (optional, default: true)
 *
 * Whether to include the symbol declaration in results.
 *
 * **Use cases:**
 * - `true` (default) - See both declaration and usages
 * - `false` - Only usages, useful for "find callers" workflows
 *
 * ## language: String (optional, default: "kotlin")
 *
 * Language identifier for LSP server selection.
 *
 * **Supported:**
 * - `"kotlin"`, `"typescript"`, `"javascript"`, `"python"`, `"bash"`
 *
 * # Returns
 *
 * Returns `Map<String, Any>` with reference locations:
 *
 * ## Success - Multiple References
 *
 * ```json
 * {
 *   "references": [
 *     {
 *       "uri": "file:///project/src/UserService.kt",
 *       "line": 15,
 *       "column": 4,
 *       "endLine": 15,
 *       "endColumn": 15,
 *       "isDeclaration": true
 *     },
 *     {
 *       "uri": "file:///project/src/UserController.kt",
 *       "line": 23,
 *       "column": 16,
 *       "endLine": 23,
 *       "endColumn": 27,
 *       "isDeclaration": false
 *     },
 *     {
 *       "uri": "file:///project/src/AuthService.kt",
 *       "line": 42,
 *       "column": 8,
 *       "endLine": 42,
 *       "endColumn": 19,
 *       "isDeclaration": false
 *     }
 *   ],
 *   "total": 3,
 *   "summary": "Found 3 references (1 declaration, 2 usages)"
 * }
 * ```
 *
 * ## Success - No References
 *
 * ```json
 * {
 *   "references": [],
 *   "total": 0,
 *   "message": "No references found for symbol at src/Utils.kt:10:5",
 *   "suggestion": "This might be dead code - consider removing"
 * }
 * ```
 *
 * ## Success - Only Declaration (unused symbol)
 *
 * ```json
 * {
 *   "references": [
 *     {
 *       "uri": "file:///project/src/Helper.kt",
 *       "line": 8,
 *       "column": 5,
 *       "isDeclaration": true
 *     }
 *   ],
 *   "total": 1,
 *   "message": "Symbol declared but never used",
 *   "suggestion": "Consider removing if not part of public API"
 * }
 * ```
 *
 * ## Error Cases
 *
 * | Error | Причина | Response |
 * |-------|---------|----------|
 * | LSP server not available | Language server not running | `{"error": "LSP server for kotlin not available"}` |
 * | File not found | Specified file doesn't exist | `{"error": "File not found: path/to/file.kt"}` |
 * | Invalid position | Line/column out of bounds | `{"error": "Invalid position"}` |
 * | Symbol not found | No symbol at specified position | `{"error": "No symbol found at position"}` |
 * | Timeout | Search took too long (large project) | `{"error": "Reference search timeout", "partial": true}` |
 *
 * # Usage Examples
 *
 * ## Example 1: Find All Function Callers
 *
 * ```json
 * {
 *   "tool": "lsp_find_references",
 *   "parameters": {
 *     "file_path": "src/services/UserService.kt",
 *     "line": 42,
 *     "column": 8,
 *     "include_declaration": false,
 *     "language": "kotlin"
 *   }
 * }
 * ```
 *
 * **Scenario:** Want to see all places calling `deleteUser` function.
 * **Result:** List of all call sites across the project.
 *
 * ## Example 2: Analyze Class Usage
 *
 * ```json
 * {
 *   "tool": "lsp_find_references",
 *   "parameters": {
 *     "file_path": "src/models/User.ts",
 *     "line": 5,
 *     "column": 13,
 *     "include_declaration": true,
 *     "language": "typescript"
 *   }
 * }
 * ```
 *
 * **Scenario:** Understanding where `User` class is instantiated or referenced.
 * **Result:** All imports, instantiations, and type annotations.
 *
 * ## Example 3: Check for Dead Code
 *
 * ```json
 * {
 *   "tool": "lsp_find_references",
 *   "parameters": {
 *     "file_path": "src/utils/deprecated.py",
 *     "line": 15,
 *     "column": 4,
 *     "include_declaration": true,
 *     "language": "python"
 *   }
 * }
 * ```
 *
 * **Scenario:** Checking if old utility function is still used.
 * **Result:** If only declaration returned → safe to delete.
 *
 * # Common Patterns
 *
 * ## Pattern: Impact Analysis for Refactoring
 *
 * ```
 * 1. lsp_find_definition on function to refactor
 * 2. lsp_find_references to see all usages
 * 3. Read each reference location with grz_read_file
 * 4. Analyze impact and plan refactoring
 * ```
 *
 * ## Pattern: Dead Code Detection
 *
 * ```
 * 1. lsp_find_references on suspected dead code
 * 2. If only declaration returned → likely dead
 * 3. Check if it's exported/public API
 * 4. Safe to remove if private and unused
 * ```
 *
 * ## Pattern: API Usage Analysis
 *
 * ```
 * 1. lsp_find_references on public API function
 * 2. Group by file/module
 * 3. Analyze usage patterns
 * 4. Document common use cases
 * ```
 *
 * # Related Tools
 *
 * **Complementary tools:**
 * - `lsp_find_definition` - Navigate to where symbol is defined
 * - `lsp_get_hover` - Get symbol signature/documentation
 * - `grz_read_file` - Read reference locations
 *
 * **Alternative approaches:**
 * - `grz_execute_command` with grep/rg - Fast but less accurate (text-based)
 * - `index_domain_to_graph` - Build full codebase graph for complex analysis
 *
 * # Performance Characteristics
 *
 * **Small projects (<100 files):**
 * - First search: ~1-2 seconds
 * - Subsequent: ~200-500ms
 *
 * **Medium projects (100-500 files):**
 * - First search: ~3-5 seconds
 * - Subsequent: ~500ms-1s
 *
 * **Large projects (1000+ files):**
 * - First search: ~10-20 seconds
 * - Subsequent: ~1-3 seconds
 *
 * **Optimization tips:**
 * - LSP servers cache results
 * - Incremental updates on file changes
 * - Project indexing happens in background
 *
 * # Implementation Notes
 *
 * **LSP Protocol:**
 * - Uses `textDocument/references` request
 * - ReferenceContext controls include_declaration
 * - Returns Location[] array
 *
 * **Smart grouping:**
 * - Results grouped by file for readability
 * - Sorted by location (file, then line, then column)
 * - Duplicates filtered
 *
 * @see lsp_find_definition For navigating to symbol definition
 * @see lsp_get_hover For getting symbol documentation
 * @see grz_read_file For reading reference context
 */
interface LspFindReferencesTool : Tool<LspFindReferencesRequest, Map<String, Any>> {
    override val name: String get() = "lsp_find_references"

    override val description: String get() =
        "Find all usages of a symbol across the project using Language Server Protocol. Shows where functions, " +
        "classes, variables are referenced. Supports Kotlin, TypeScript, Python, Bash. Use for impact analysis, " +
        "refactoring planning, or finding dead code."

    override val requestType: Class<LspFindReferencesRequest> get() =
        LspFindReferencesRequest::class.java
}
