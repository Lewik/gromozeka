package com.gromozeka.domain.tool.lsp

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for lsp_get_hover tool.
 *
 * @property file_path Path to the file (absolute or relative to project root)
 * @property line Zero-based line number where the symbol is located
 * @property column Zero-based column number where the symbol is located
 * @property language Language identifier for LSP server selection (kotlin, typescript, python, etc.)
 */
data class LspGetHoverRequest(
    val file_path: String,
    val line: Int,
    val column: Int,
    val language: String = "kotlin"
)

/**
 * Domain specification for LSP get hover information tool.
 *
 * # MCP Tool Exposure
 *
 * **Tool name:** `lsp_get_hover`
 *
 * **Exposed via:** Spring AI + MCP protocol
 *
 * # Purpose
 *
 * Get documentation, type information, and signatures for symbols at a specific code location.
 * Provides the same information shown in IDE hover tooltips using Language Server Protocol.
 *
 * ## Core Features
 *
 * **Rich documentation:**
 * - Function/method signatures with parameter types
 * - KDoc/JSDoc/docstring documentation
 * - Return type information
 * - Deprecation warnings
 * - Code examples from documentation
 *
 * **Type information:**
 * - Inferred types for variables
 * - Generic type parameters
 * - Type aliases resolution
 * - Union/intersection types (TypeScript)
 *
 * **Quick reference:**
 * - No navigation required (vs find_definition)
 * - Instant context without leaving current file
 * - Formatted markdown for readability
 *
 * # When to Use
 *
 * **Use lsp_get_hover when:**
 * - Need to understand function parameters
 * - Want to see return type of an expression
 * - Reading documentation without navigating away
 * - Checking if symbol is deprecated
 * - Understanding complex type signatures
 * - Learning API usage from inline docs
 *
 * **Don't use when:**
 * - Need to see implementation → use `lsp_find_definition`
 * - Need all usages → use `lsp_find_references`
 * - Need compilation errors → use `lsp_get_diagnostics`
 * - Need full API docs → use `jina_read_url` for online docs
 *
 * # Parameters
 *
 * ## file_path: String (required)
 *
 * Path to file containing the symbol.
 *
 * **Examples:**
 * - `"src/main/kotlin/UserService.kt"`
 * - `"src/components/Button.tsx"`
 *
 * ## line: Int (required)
 *
 * Zero-based line number where the symbol is located.
 *
 * ## column: Int (required)
 *
 * Zero-based column number where the symbol is located.
 *
 * **Tips:**
 * - Point anywhere within symbol name
 * - Hover works on types, functions, variables, parameters
 *
 * ## language: String (optional, default: "kotlin")
 *
 * Language identifier for LSP server selection.
 *
 * # Returns
 *
 * Returns `Map<String, Any>` with hover information:
 *
 * ## Success - Function with Documentation
 *
 * ```json
 * {
 *   "content": "```kotlin\nfun fetchUser(id: String): User?\n```\n\n---\n\n**Fetches user by ID from database.**\n\nReturns null if user not found.\n\n**Parameters:**\n- `id` - User identifier (UUID format)\n\n**Returns:** User object or null\n\n**Throws:**\n- `DatabaseException` if connection fails",
 *   "range": {
 *     "start": {"line": 15, "column": 4},
 *     "end": {"line": 15, "column": 13}
 *   }
 * }
 * ```
 *
 * ## Success - Type Information
 *
 * ```json
 * {
 *   "content": "```typescript\nconst user: User | null\n```\n\nInferred type from `fetchUser` return value",
 *   "range": {
 *     "start": {"line": 23, "column": 6},
 *     "end": {"line": 23, "column": 10}
 *   }
 * }
 * ```
 *
 * ## Success - Deprecated Symbol
 *
 * ```json
 * {
 *   "content": "```kotlin\n@Deprecated(\"Use newFunction instead\")\nfun oldFunction(): Unit\n```\n\n⚠️ **DEPRECATED**\n\nThis function is deprecated. Use `newFunction` instead.\n\nWill be removed in version 2.0.0",
 *   "range": {
 *     "start": {"line": 42, "column": 5},
 *     "end": {"line": 42, "column": 16}
 *   },
 *   "deprecated": true
 * }
 * ```
 *
 * ## Success - No Information Available
 *
 * ```json
 * {
 *   "content": null,
 *   "message": "No hover information available at this location"
 * }
 * ```
 *
 * ## Error Cases
 *
 * | Error | Причина | Response |
 * |-------|---------|----------|
 * | LSP server not available | Language server not running | `{"error": "LSP server for kotlin not available"}` |
 * | File not found | File doesn't exist | `{"error": "File not found"}` |
 * | Invalid position | Line/column out of bounds | `{"error": "Invalid position"}` |
 * | Not a symbol | Position not on any symbol | `{"content": null}` |
 *
 * # Usage Examples
 *
 * ## Example 1: Understanding Function Signature
 *
 * ```json
 * {
 *   "tool": "lsp_get_hover",
 *   "parameters": {
 *     "file_path": "src/services/UserService.kt",
 *     "line": 25,
 *     "column": 10,
 *     "language": "kotlin"
 *   }
 * }
 * ```
 *
 * **Scenario:** Cursor on `saveUser` call, need to know parameters.
 * **Result:** Shows function signature with parameter types and documentation.
 *
 * ## Example 2: Checking Inferred Type
 *
 * ```json
 * {
 *   "tool": "lsp_get_hover",
 *   "parameters": {
 *     "file_path": "src/app.ts",
 *     "line": 15,
 *     "column": 8,
 *     "language": "typescript"
 *   }
 * }
 * ```
 *
 * **Scenario:** Variable declared with `const result = ...`, what's the type?
 * **Result:** Shows inferred type from TypeScript compiler.
 *
 * ## Example 3: Reading API Documentation
 *
 * ```json
 * {
 *   "tool": "lsp_get_hover",
 *   "parameters": {
 *     "file_path": "src/main.py",
 *     "line": 8,
 *     "column": 5,
 *     "language": "python"
 *   }
 * }
 * ```
 *
 * **Scenario:** Using third-party library function, need to understand it.
 * **Result:** Shows docstring with usage examples.
 *
 * # Common Patterns
 *
 * ## Pattern: Learning API While Coding
 *
 * ```
 * 1. Read file with grz_read_file
 * 2. See unfamiliar function call
 * 3. lsp_get_hover on function name
 * 4. Read signature and docs
 * 5. Continue without navigating away
 * ```
 *
 * ## Pattern: Type Debugging
 *
 * ```
 * 1. Expression has unexpected type error
 * 2. lsp_get_hover on variable
 * 3. Check inferred vs expected type
 * 4. Trace back to find type mismatch
 * ```
 *
 * ## Pattern: Deprecation Check
 *
 * ```
 * 1. lsp_get_hover on function
 * 2. Check for @Deprecated annotation
 * 3. Read replacement suggestion
 * 4. lsp_find_references to find all usages needing update
 * ```
 *
 * # Related Tools
 *
 * **Complementary tools:**
 * - `lsp_find_definition` - Navigate to full implementation
 * - `lsp_find_references` - See where symbol is used
 * - `lsp_get_diagnostics` - Get compilation errors
 * - `grz_read_file` - Read broader context
 *
 * **Alternative approaches:**
 * - `jina_read_url` - For online API documentation
 * - `brave_web_search` - Search for usage examples
 *
 * # Performance Characteristics
 *
 * **Response time:**
 * - Cold start: ~2-5 seconds (server initialization)
 * - Warm: ~50-200ms
 * - Very fast compared to find_definition/references
 *
 * **Scaling:**
 * - Performance independent of project size
 * - Hover info cached by LSP server
 * - Instant for previously queried symbols
 *
 * # Implementation Notes
 *
 * **LSP Protocol:**
 * - Uses `textDocument/hover` request
 * - Returns MarkupContent (markdown or plaintext)
 * - Range indicates hover target span
 *
 * **Content formatting:**
 * - Markdown rendered with code blocks
 * - Syntax highlighting for signatures
 * - Structured sections (params, returns, throws)
 *
 * **Language-specific features:**
 * - Kotlin: KDoc with @param, @return tags
 * - TypeScript: JSDoc with @deprecated, @example
 * - Python: Docstrings with reStructuredText
 *
 * @see lsp_find_definition For navigating to implementation
 * @see lsp_find_references For finding all usages
 * @see grz_read_file For reading full file context
 */
interface LspGetHoverTool : Tool<LspGetHoverRequest, Map<String, Any>> {
    override val name: String get() = "lsp_get_hover"

    override val description: String get() =
        "Get documentation and type information for a symbol using Language Server Protocol. Shows function " +
        "signatures, parameter types, return types, deprecation warnings, and inline documentation. Quick " +
        "reference without navigation. Supports Kotlin, TypeScript, Python, Bash."

    override val requestType: Class<LspGetHoverRequest> get() =
        LspGetHoverRequest::class.java
}
