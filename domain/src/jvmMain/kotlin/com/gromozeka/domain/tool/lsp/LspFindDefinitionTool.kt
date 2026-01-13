package com.gromozeka.domain.tool.lsp

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for lsp_find_definition tool.
 *
 * @property file_path Path to the file (absolute or relative to project root)
 * @property line Zero-based line number where the symbol is located
 * @property column Zero-based column number where the symbol is located
 * @property language Language identifier for LSP server selection (kotlin, typescript, python, etc.)
 */
data class LspFindDefinitionRequest(
    val file_path: String,
    val line: Int,
    val column: Int,
    val language: String = "kotlin"
)

/**
 * Domain specification for LSP find definition tool.
 *
 * # MCP Tool Exposure
 *
 * **Tool name:** `lsp_find_definition`
 *
 * **Exposed via:** Spring AI + MCP protocol
 *
 * # Purpose
 *
 * Navigate to the definition of a symbol (function, class, variable, etc.) using Language Server Protocol.
 * Provides semantic code navigation similar to "Go to Definition" in modern IDEs.
 *
 * ## Core Features
 *
 * **Semantic navigation:**
 * - Finds exact definition location of symbols
 * - Works across files and modules
 * - Understands language-specific scoping rules
 * - Handles imports, references, and type hierarchies
 *
 * **Multi-language support:**
 * - Kotlin (via kotlin-language-server)
 * - TypeScript/JavaScript (via typescript-language-server)
 * - Python (via pyright)
 * - Bash (via bash-language-server)
 * - And more through LSP protocol
 *
 * **Intelligent analysis:**
 * - Resolves overloaded functions
 * - Follows type aliases
 * - Handles generic types
 * - Understands language-specific constructs
 *
 * # When to Use
 *
 * **Use lsp_find_definition when:**
 * - Need to understand where a function is implemented
 * - Exploring unfamiliar codebase structure
 * - Tracing data flow through the code
 * - Finding class or interface declarations
 * - Understanding library API implementations
 * - Navigating to configuration definitions
 *
 * **Don't use when:**
 * - Need to find all usages of a symbol → use `lsp_find_references`
 * - Need documentation for a symbol → use `lsp_get_hover`
 * - Looking for compilation errors → use `lsp_get_diagnostics`
 * - Searching by text pattern → use `grz_execute_command` with grep/rg
 *
 * # Parameters
 *
 * ## file_path: String (required)
 *
 * Path to the file containing the symbol. Can be absolute or relative to project root.
 *
 * **Examples:**
 * - `"src/main/kotlin/Main.kt"` - Relative path
 * - `"/absolute/path/to/file.ts"` - Absolute path
 * - `"./services/UserService.py"` - Current directory
 *
 * **Validation:**
 * - File must exist
 * - File must be of supported language type
 * - Returns error if file not found
 *
 * ## line: Int (required)
 *
 * Zero-based line number where the symbol is located.
 *
 * **Important:**
 * - Lines are zero-indexed (first line is 0)
 * - Must be within file bounds
 * - Position should point to the symbol identifier
 *
 * **Examples:**
 * - `0` - First line
 * - `42` - Line 43 in editor (editor shows 1-based)
 *
 * ## column: Int (required)
 *
 * Zero-based column number (character offset) where the symbol is located.
 *
 * **Important:**
 * - Columns are zero-indexed (first character is 0)
 * - Should point to start or middle of symbol name
 * - UTF-8 aware (multi-byte characters count as one)
 *
 * **Examples:**
 * - `0` - First character
 * - `15` - Character at offset 15
 *
 * ## language: String (optional, default: "kotlin")
 *
 * Language identifier for selecting appropriate LSP server.
 *
 * **Supported languages:**
 * - `"kotlin"` - Kotlin language server
 * - `"typescript"` - TypeScript/JavaScript language server
 * - `"javascript"` - TypeScript/JavaScript language server
 * - `"python"` - Pyright language server
 * - `"bash"` - Bash language server
 *
 * **Automatic detection:**
 * Can be inferred from file extension, but explicit specification is more reliable.
 *
 * # Returns
 *
 * Returns `Map<String, Any>` with definition locations:
 *
 * ## Success - Single Definition
 *
 * ```json
 * {
 *   "locations": [
 *     {
 *       "uri": "file:///Users/user/project/src/UserService.kt",
 *       "line": 42,
 *       "column": 5,
 *       "endLine": 42,
 *       "endColumn": 16
 *     }
 *   ]
 * }
 * ```
 *
 * ## Success - Multiple Definitions (e.g., overloaded functions)
 *
 * ```json
 * {
 *   "locations": [
 *     {
 *       "uri": "file:///path/to/FileA.kt",
 *       "line": 10,
 *       "column": 5,
 *       "endLine": 10,
 *       "endColumn": 20
 *     },
 *     {
 *       "uri": "file:///path/to/FileB.kt",
 *       "line": 25,
 *       "column": 5,
 *       "endLine": 25,
 *       "endColumn": 20
 *     }
 *   ]
 * }
 * ```
 *
 * ## Success - No Definition Found
 *
 * ```json
 * {
 *   "locations": [],
 *   "message": "No definition found at src/Main.kt:10:5"
 * }
 * ```
 *
 * ## Error Cases
 *
 * | Error | Причина | Response |
 * |-------|---------|----------|
 * | LSP server not available | Language server not installed or failed to start | `{"error": "LSP server for kotlin not available"}` |
 * | File not found | Specified file doesn't exist | `{"error": "File not found: path/to/file.kt"}` |
 * | Invalid position | Line/column out of bounds | `{"error": "Invalid position: line 1000 exceeds file length"}` |
 * | Initialization timeout | LSP server took too long to initialize | `{"error": "LSP server initialization timeout"}` |
 * | Language not supported | No LSP server configured for language | `{"error": "Language 'ruby' not supported"}` |
 *
 * # Usage Examples
 *
 * ## Example 1: Find Function Definition
 *
 * ```json
 * {
 *   "tool": "lsp_find_definition",
 *   "parameters": {
 *     "file_path": "src/main/kotlin/UserService.kt",
 *     "line": 15,
 *     "column": 20,
 *     "language": "kotlin"
 *   }
 * }
 * ```
 *
 * **Scenario:** User is on line 16 (editor), column 21, hovering over `fetchUser` call.
 * **Result:** Returns location where `fetchUser` function is defined.
 *
 * ## Example 2: Navigate to Class Declaration
 *
 * ```json
 * {
 *   "tool": "lsp_find_definition",
 *   "parameters": {
 *     "file_path": "src/services/impl/UserServiceImpl.ts",
 *     "line": 5,
 *     "column": 30,
 *     "language": "typescript"
 *   }
 * }
 * ```
 *
 * **Scenario:** Cursor on `UserRepository` type in import or usage.
 * **Result:** Navigates to `UserRepository` interface definition.
 *
 * ## Example 3: Find Variable Declaration
 *
 * ```json
 * {
 *   "tool": "lsp_find_definition",
 *   "parameters": {
 *     "file_path": "scripts/deploy.sh",
 *     "line": 42,
 *     "column": 10,
 *     "language": "bash"
 *   }
 * }
 * ```
 *
 * **Scenario:** Cursor on `$DATABASE_URL` variable usage.
 * **Result:** Finds where `DATABASE_URL` is defined/exported.
 *
 * # Common Patterns
 *
 * ## Pattern: Exploring Unfamiliar Code
 *
 * ```
 * 1. Read file with grz_read_file to see code
 * 2. Identify interesting function/class on line X
 * 3. Use lsp_find_definition at that position
 * 4. Read the definition file
 * 5. Repeat to navigate deeper
 * ```
 *
 * ## Pattern: Tracing Data Flow
 *
 * ```
 * 1. Start at function call
 * 2. lsp_find_definition → find implementation
 * 3. Read implementation
 * 4. Find next function call in implementation
 * 5. Repeat to trace full data pipeline
 * ```
 *
 * ## Pattern: Understanding Type Hierarchy
 *
 * ```
 * 1. lsp_find_definition on interface usage → get interface
 * 2. lsp_find_references on interface → get implementations
 * 3. lsp_find_definition on each implementation
 * 4. Build mental model of type hierarchy
 * ```
 *
 * # Related Tools
 *
 * **Complementary navigation tools:**
 * - `lsp_find_references` - Find all usages of this symbol
 * - `lsp_get_hover` - Get documentation/signature without navigation
 * - `lsp_get_diagnostics` - Find errors in the file
 *
 * **Alternative approaches:**
 * - `grz_execute_command` with grep/rg - Text-based search (less accurate)
 * - `index_domain_to_graph` - Build knowledge graph of entire codebase
 *
 * # Performance Characteristics
 *
 * **First call (cold start):**
 * - LSP server initialization: 2-5 seconds (Kotlin)
 * - Definition lookup: ~100-500ms
 * - Total: ~2-6 seconds
 *
 * **Subsequent calls (warm):**
 * - Definition lookup: ~50-200ms
 * - LSP server stays running between calls
 *
 * **Scaling:**
 * - Large projects (1000+ files): initialization may take 10-15s
 * - Performance improves after project indexing complete
 * - Server maintains cache between requests
 *
 * # Implementation Notes
 *
 * **LSP Protocol Details:**
 * - Uses `textDocument/definition` LSP request
 * - Returns either Location or Location[] or LocationLink[]
 * - Handles URI conversion (file:// protocol)
 *
 * **Server Management:**
 * - One LSP server process per language per project
 * - Servers are cached and reused
 * - Automatic cleanup on tool shutdown
 *
 * @see lsp_find_references For finding all usages of a symbol
 * @see lsp_get_hover For getting symbol documentation
 * @see grz_read_file For reading definition file contents
 */
interface LspFindDefinitionTool : Tool<LspFindDefinitionRequest, Map<String, Any>> {
    override val name: String get() = "lsp_find_definition"

    override val description: String get() =
        "Find symbol definition using Language Server Protocol. Navigate to where functions, classes, " +
        "variables are defined. Works across files with semantic understanding. Supports Kotlin, TypeScript, " +
        "Python, Bash and more. Use when exploring code structure or tracing implementations."

    override val requestType: Class<LspFindDefinitionRequest> get() =
        LspFindDefinitionRequest::class.java
}
