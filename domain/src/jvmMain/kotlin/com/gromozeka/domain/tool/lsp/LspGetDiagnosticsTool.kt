package com.gromozeka.domain.tool.lsp

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for lsp_get_diagnostics tool.
 *
 * @property file_path Path to the file (absolute or relative to project root)
 * @property language Language identifier for LSP server selection (kotlin, typescript, python, etc.)
 */
data class LspGetDiagnosticsRequest(
    val file_path: String,
    val language: String = "kotlin"
)

/**
 * Domain specification for LSP get diagnostics tool.
 *
 * # MCP Tool Exposure
 *
 * **Tool name:** `lsp_get_diagnostics`
 *
 * **Exposed via:** Spring AI + MCP protocol
 *
 * # Purpose
 *
 * Get compilation errors, warnings, and hints for a file using Language Server Protocol.
 * Provides real-time feedback similar to IDE error highlighting without running build tools.
 *
 * ## Core Features
 *
 * **Real-time analysis:**
 * - Syntax errors (parse failures)
 * - Type errors (type mismatches, undefined symbols)
 * - Semantic errors (unreachable code, unused variables)
 * - Warnings (deprecated API usage, code smells)
 * - Hints (suggestions, optimization opportunities)
 *
 * **Detailed information:**
 * - Exact error location (line, column, range)
 * - Clear error messages
 * - Severity levels (error, warning, info, hint)
 * - Error codes for lookup
 * - Related information (e.g., conflicting declarations)
 *
 * **Multi-language support:**
 * - Language-specific diagnostics
 * - Linter integration (ESLint, Pylint, etc.)
 * - Formatter suggestions
 * - Custom rule violations
 *
 * # When to Use
 *
 * **Use lsp_get_diagnostics when:**
 * - Checking if code compiles before running build
 * - Finding errors in newly written code
 * - Validating edits made by grz_edit_file/grz_write_file
 * - Understanding why code isn't working
 * - Finding all issues in a file at once
 * - Checking for warnings before commit
 *
 * **Don't use when:**
 * - Need full build output → use `grz_execute_command` with build tool
 * - Need test results → run tests via build system
 * - Need runtime errors → check application logs
 * - Just need to read code → use `grz_read_file`
 *
 * # Parameters
 *
 * ## file_path: String (required)
 *
 * Path to file to analyze for diagnostics.
 *
 * **Examples:**
 * - `"src/main/kotlin/Main.kt"` - Kotlin source file
 * - `"src/app.ts"` - TypeScript file
 * - `"scripts/deploy.sh"` - Bash script
 *
 * **Validation:**
 * - File must exist
 * - File must be supported language
 * - File must be part of indexed project
 *
 * ## language: String (optional, default: "kotlin")
 *
 * Language identifier for LSP server selection.
 *
 * **Supported:**
 * - `"kotlin"` - Kotlin compiler diagnostics
 * - `"typescript"` - TypeScript compiler + ESLint
 * - `"javascript"` - ESLint
 * - `"python"` - Pyright type checker + Pylint
 * - `"bash"` - ShellCheck linter
 *
 * # Returns
 *
 * Returns `Map<String, Any>` with diagnostics information:
 *
 * ## Success - File with Errors
 *
 * ```json
 * {
 *   "diagnostics": [
 *     {
 *       "severity": "error",
 *       "message": "Unresolved reference: invalidFunction",
 *       "line": 15,
 *       "column": 8,
 *       "endLine": 15,
 *       "endColumn": 23,
 *       "code": "UNRESOLVED_REFERENCE",
 *       "source": "kotlin"
 *     },
 *     {
 *       "severity": "warning",
 *       "message": "Variable 'unused' is never used",
 *       "line": 10,
 *       "column": 8,
 *       "endLine": 10,
 *       "endColumn": 14,
 *       "code": "UNUSED_VARIABLE",
 *       "source": "kotlin"
 *     },
 *     {
 *       "severity": "info",
 *       "message": "This expression could be simplified",
 *       "line": 25,
 *       "column": 12,
 *       "endLine": 25,
 *       "endColumn": 35,
 *       "source": "kotlin"
 *     }
 *   ],
 *   "summary": {
 *     "errors": 1,
 *     "warnings": 1,
 *     "info": 1,
 *     "hints": 0,
 *     "total": 3
 *   },
 *   "hasErrors": true
 * }
 * ```
 *
 * ## Success - Clean File
 *
 * ```json
 * {
 *   "diagnostics": [],
 *   "summary": {
 *     "errors": 0,
 *     "warnings": 0,
 *     "info": 0,
 *     "hints": 0,
 *     "total": 0
 *   },
 *   "hasErrors": false,
 *   "message": "No issues found in src/Main.kt"
 * }
 * ```
 *
 * ## Success - TypeScript with ESLint
 *
 * ```json
 * {
 *   "diagnostics": [
 *     {
 *       "severity": "error",
 *       "message": "Type 'string' is not assignable to type 'number'",
 *       "line": 12,
 *       "column": 10,
 *       "endLine": 12,
 *       "endColumn": 15,
 *       "code": "2322",
 *       "source": "typescript"
 *     },
 *     {
 *       "severity": "warning",
 *       "message": "Unexpected console statement",
 *       "line": 20,
 *       "column": 4,
 *       "endLine": 20,
 *       "endColumn": 18,
 *       "code": "no-console",
 *       "source": "eslint"
 *     }
 *   ],
 *   "summary": {
 *     "errors": 1,
 *     "warnings": 1,
 *     "info": 0,
 *     "hints": 0,
 *     "total": 2
 *   },
 *   "hasErrors": true
 * }
 * ```
 *
 * ## Error Cases
 *
 * | Error | Причина | Response |
 * |-------|---------|----------|
 * | LSP server not available | Language server not running | `{"error": "LSP server for kotlin not available"}` |
 * | File not found | File doesn't exist | `{"error": "File not found: path/to/file.kt"}` |
 * | File not indexed | File outside project scope | `{"error": "File not part of indexed project"}` |
 * | Server initialization | LSP server still starting | `{"error": "LSP server initializing, try again"}` |
 *
 * # Usage Examples
 *
 * ## Example 1: Validate After Edit
 *
 * ```json
 * {
 *   "tool": "lsp_get_diagnostics",
 *   "parameters": {
 *     "file_path": "src/services/UserService.kt",
 *     "language": "kotlin"
 *   }
 * }
 * ```
 *
 * **Scenario:** Just edited file with grz_edit_file, want to check for errors.
 * **Result:** Shows any compilation errors introduced by the edit.
 *
 * ## Example 2: Pre-Commit Validation
 *
 * ```json
 * {
 *   "tool": "lsp_get_diagnostics",
 *   "parameters": {
 *     "file_path": "src/components/Button.tsx",
 *     "language": "typescript"
 *   }
 * }
 * ```
 *
 * **Scenario:** Before committing, check for any warnings or errors.
 * **Result:** TypeScript errors + ESLint warnings.
 *
 * ## Example 3: Debugging Mysterious Issue
 *
 * ```json
 * {
 *   "tool": "lsp_get_diagnostics",
 *   "parameters": {
 *     "file_path": "scripts/build.sh",
 *     "language": "bash"
 *   }
 * }
 * ```
 *
 * **Scenario:** Script not working, check for shell errors.
 * **Result:** ShellCheck warnings about common pitfalls.
 *
 * # Common Patterns
 *
 * ## Pattern: Safe Code Modification
 *
 * ```
 * 1. Read file with grz_read_file
 * 2. Make changes with grz_edit_file
 * 3. lsp_get_diagnostics to validate
 * 4. If errors: fix or revert
 * 5. If clean: proceed
 * ```
 *
 * ## Pattern: Error-Driven Development
 *
 * ```
 * 1. Write code quickly with grz_write_file
 * 2. lsp_get_diagnostics to find all issues
 * 3. Fix errors one by one
 * 4. Repeat until clean
 * ```
 *
 * ## Pattern: Quality Check
 *
 * ```
 * 1. Get list of recently modified files
 * 2. Run lsp_get_diagnostics on each
 * 3. Collect all warnings/errors
 * 4. Prioritize and fix
 * ```
 *
 * # Related Tools
 *
 * **Complementary tools:**
 * - `grz_edit_file` - Make changes, then validate with this
 * - `grz_write_file` - Write new code, then check for errors
 * - `lsp_get_hover` - Get details about error location
 * - `lsp_find_definition` - Navigate to referenced symbols
 *
 * **Alternative approaches:**
 * - `grz_execute_command` with build - Full project build (slower but complete)
 * - `grz_execute_command` with linter - Dedicated linter run
 *
 * # Performance Characteristics
 *
 * **Response time:**
 * - Cold start: ~2-5 seconds (server init + file analysis)
 * - Warm (file already analyzed): ~50-200ms
 * - Incremental (after file edit): ~100-300ms
 *
 * **Scaling:**
 * - Small files (<500 lines): ~50ms
 * - Large files (5000+ lines): ~500ms
 * - Performance degrades with complex type hierarchies
 *
 * **Optimization:**
 * - LSP servers cache analysis results
 * - Incremental re-analysis on edits
 * - Background analysis while idle
 *
 * # Implementation Notes
 *
 * **LSP Protocol:**
 * - Uses `textDocument/publishDiagnostics` notifications
 * - Diagnostics pushed by server on file changes
 * - Client queries latest diagnostics from cache
 *
 * **Severity levels:**
 * - Error (1): Compilation failures, must fix
 * - Warning (2): Code smells, should fix
 * - Information (3): Suggestions, optional
 * - Hint (4): Minor improvements, optional
 *
 * **Sources:**
 * - Compiler (kotlin, typescript, etc.)
 * - Linters (eslint, pylint, shellcheck)
 * - Formatters (prettier suggestions)
 * - Custom rules (project-specific)
 *
 * **Related information:**
 * - Some diagnostics include related locations
 * - E.g., "Duplicate declaration" points to both declarations
 * - Helps understand context of the error
 *
 * @see grz_edit_file For making code changes
 * @see grz_write_file For creating new files
 * @see lsp_get_hover For getting details about symbols
 * @see grz_execute_command For running full build
 */
interface LspGetDiagnosticsTool : Tool<LspGetDiagnosticsRequest, Map<String, Any>> {
    override val name: String get() = "lsp_get_diagnostics"

    override val description: String get() =
        "Get compilation errors, warnings, and hints for a file using Language Server Protocol. Shows syntax " +
        "errors, type errors, linter warnings, and code suggestions. Real-time analysis without running build. " +
        "Supports Kotlin, TypeScript, Python, Bash. Use after editing files to validate changes."

    override val requestType: Class<LspGetDiagnosticsRequest> get() =
        LspGetDiagnosticsRequest::class.java
}
