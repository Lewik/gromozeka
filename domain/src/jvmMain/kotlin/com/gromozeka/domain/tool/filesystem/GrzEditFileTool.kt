package com.gromozeka.domain.tool.filesystem

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for grz_edit_file tool.
 * 
 * @property file_path Path to the file (absolute or relative to project root)
 * @property old_string Exact string to find and replace
 * @property new_string Replacement string
 * @property replace_all Replace all occurrences (default: false, requires unique match)
 */
data class EditFileRequest(
    val file_path: String,
    val old_string: String,
    val new_string: String,
    val replace_all: Boolean = false
)

/**
 * Domain specification for precise file editing tool.
 * 
 * # MCP Tool Exposure
 * 
 * **Tool name:** `grz_edit_file`
 * 
 * **Exposed via:** Spring AI + MCP protocol
 * 
 * # Purpose
 * 
 * Perform exact string replacements in files without rewriting entire content.
 * More efficient and safer than read-modify-write pattern for targeted changes.
 * 
 * ## Core Features
 * 
 * **Exact match requirement:**
 * - Searches for literal `old_string` (not regex)
 * - Preserves indentation, whitespace, formatting
 * - Case-sensitive matching
 * - Must match byte-for-byte
 * 
 * **Uniqueness enforcement:**
 * - By default, fails if `old_string` appears multiple times
 * - Forces explicit choice: add context to make unique, or use `replace_all=true`
 * - Prevents accidental multi-replacements
 * 
 * **Efficient operation:**
 * - Reads file once, replaces, writes once
 * - No need to read file content into LLM context
 * - Much faster than `grz_read_file` + modify + `grz_write_file`
 * 
 * # When to Use
 * 
 * **Use grz_edit_file when:**
 * - Changing specific function/method in large file
 * - Updating single configuration value
 * - Renaming symbol in one location
 * - Fixing typo or bug in known location
 * - Replacing all occurrences of variable name
 * 
 * **Don't use when:**
 * - Need to see file context to decide what to change → use `grz_read_file` first
 * - Making multiple unrelated changes → consider separate edits or `grz_write_file`
 * - Creating new file → use `grz_write_file`
 * - Need regex-based replacement → use `grz_execute_command` with `sed`
 * 
 * # Parameters
 * 
 * ## file_path: String (required)
 * 
 * Path to the file to edit.
 * 
 * **Examples:**
 * - `"src/main/kotlin/Service.kt"` - Relative path
 * - `"/absolute/path/to/config.json"` - Absolute path
 * 
 * **Validation:**
 * - File must exist (returns error otherwise)
 * - Must be regular file, not directory
 * 
 * ## old_string: String (required)
 * 
 * Exact string to find and replace.
 * 
 * **Matching rules:**
 * - Exact byte-for-byte match (no fuzzy matching)
 * - Case-sensitive
 * - Whitespace significant (spaces, tabs, newlines matter)
 * - No regex interpretation (literal string only)
 * 
 * **Best practices:**
 * - Include surrounding context to make match unique
 * - Include indentation to match formatting
 * - Use complete logical units (full lines, statements, blocks)
 * 
 * **Anti-patterns:**
 * - Too short: `old_string="x"` → likely multiple matches
 * - Missing context: `old_string="return true"` → ambiguous
 * - Wrong whitespace: `old_string="if (x) {"` but file has `if(x){`
 * 
 * ## new_string: String (required)
 * 
 * Replacement string.
 * 
 * **Replacement behavior:**
 * - Exact substitution (no transformations)
 * - Can be empty (deletion)
 * - Can be longer or shorter than `old_string`
 * - Preserves surrounding content
 * 
 * **Validation:**
 * - Must differ from `old_string` (error otherwise)
 * - Can be empty string (for deletion)
 * 
 * ## replace_all: Boolean (optional, default: false)
 * 
 * Replace all occurrences instead of requiring unique match.
 * 
 * **Default behavior (replace_all=false):**
 * - Fails if `old_string` appears 0 times → "not found"
 * - Fails if `old_string` appears >1 times → "not unique, use replace_all or add context"
 * - Succeeds only if exactly 1 occurrence
 * 
 * **With replace_all=true:**
 * - Replaces all occurrences
 * - Returns count of replacements
 * - Useful for renaming symbols across file
 * 
 * **When to use replace_all:**
 * - Renaming variable used multiple times
 * - Updating all imports of a package
 * - Changing all instances of deprecated API
 * 
 * # Returns
 * 
 * Returns `Map<String, Any>` with operation result:
 * 
 * ## Success Response
 * 
 * ```json
 * {
 *   "success": true,
 *   "path": "/absolute/path/to/file.kt",
 *   "replacements": 1,
 *   "bytes": 5678
 * }
 * ```
 * 
 * **Fields:**
 * - `success` - Always `true` on success
 * - `path` - Absolute path to modified file
 * - `replacements` - Number of replacements made (1 or more)
 * - `bytes` - New file size after edit
 * 
 * ## Error Response
 * 
 * ```json
 * {
 *   "error": "old_string appears 3 times in file. Use replace_all=true to replace all occurrences, or provide more context to make old_string unique."
 * }
 * ```
 * 
 * # Error Cases
 * 
 * | Error | Reason | Solution |
 * |-------|--------|----------|
 * | File not found | Path doesn't exist | Check path spelling |
 * | Path is not a file | Path points to directory | Use correct file path |
 * | old_string and new_string must be different | Same value for both | Change one of them |
 * | old_string cannot be empty | Empty old_string provided | Provide actual string to find |
 * | old_string not found in file | String doesn't exist | Check spelling, whitespace, case |
 * | old_string appears N times | Multiple matches without replace_all | Add context or use replace_all=true |
 * | Error editing file | IO exception | Check permissions, disk space |
 * 
 * # Usage Examples
 * 
 * ## Example 1: Fix Bug in Function
 * 
 * **Use case:** Change return value in specific function
 * 
 * ```json
 * {
 *   "tool": "grz_edit_file",
 *   "parameters": {
 *     "file_path": "src/main/kotlin/Service.kt",
 *     "old_string": "    fun isValid(): Boolean {\n        return false\n    }",
 *     "new_string": "    fun isValid(): Boolean {\n        return true\n    }"
 *   }
 * }
 * ```
 * 
 * **Result:** Single occurrence replaced (includes indentation and context)
 * 
 * ## Example 2: Rename Variable (All Occurrences)
 * 
 * **Use case:** Rename variable throughout file
 * 
 * ```json
 * {
 *   "tool": "grz_edit_file",
 *   "parameters": {
 *     "file_path": "src/Controller.kt",
 *     "old_string": "oldVariableName",
 *     "new_string": "newVariableName",
 *     "replace_all": true
 *   }
 * }
 * ```
 * 
 * **Result:** All 5 occurrences replaced, returns `"replacements": 5`
 * 
 * ## Example 3: Update Configuration Value
 * 
 * **Use case:** Change single config setting
 * 
 * ```json
 * {
 *   "tool": "grz_edit_file",
 *   "parameters": {
 *     "file_path": "application.yaml",
 *     "old_string": "  port: 8080",
 *     "new_string": "  port: 9000"
 *   }
 * }
 * ```
 * 
 * **Result:** Port value updated (whitespace preserved)
 * 
 * ## Example 4: Add Context to Make Match Unique
 * 
 * **Problem:** File has multiple `return true` statements
 * 
 * **Wrong approach (fails):**
 * ```json
 * {
 *   "old_string": "return true"
 * }
 * ```
 * → Error: "old_string appears 3 times"
 * 
 * **Correct approach (succeeds):**
 * ```json
 * {
 *   "old_string": "fun isAuthenticated(): Boolean {\n        return true\n    }"
 * }
 * ```
 * → Success: Unique match with function context
 * 
 * ## Example 5: Delete Code Block
 * 
 * **Use case:** Remove debugging code
 * 
 * ```json
 * {
 *   "tool": "grz_edit_file",
 *   "parameters": {
 *     "file_path": "src/Debug.kt",
 *     "old_string": "    // Debug logging\n    println(\"Debug: $value\")\n    ",
 *     "new_string": ""
 *   }
 * }
 * ```
 * 
 * **Result:** Debug block removed (new_string is empty)
 * 
 * ## Example 6: Update Import Statement
 * 
 * **Use case:** Change imported package
 * 
 * ```json
 * {
 *   "tool": "grz_edit_file",
 *   "parameters": {
 *     "file_path": "src/Main.kt",
 *     "old_string": "import com.old.package.Service",
 *     "new_string": "import com.new.package.Service"
 *   }
 * }
 * ```
 * 
 * **Result:** Import updated
 * 
 * # Common Patterns
 * 
 * ## Pattern: Targeted Function Edit
 * 
 * Include enough context to uniquely identify the function:
 * 
 * ```kotlin
 * old_string = """
 *     fun calculateTotal(items: List<Item>): Double {
 *         return items.sumOf { it.price }
 *     }
 * """
 * 
 * new_string = """
 *     fun calculateTotal(items: List<Item>): Double {
 *         return items.sumOf { it.price * it.quantity }
 *     }
 * """
 * ```
 * 
 * ## Pattern: Multi-Step Refactoring
 * 
 * For complex changes, make multiple targeted edits:
 * 
 * 1. `grz_edit_file` - Rename function signature
 * 2. `grz_edit_file` - Update function body
 * 3. `grz_edit_file` - Update call sites (replace_all=true)
 * 
 * **Note:** Each edit is independent, some may fail if previous edit changed the file.
 * 
 * ## Pattern: Safe Edit (Read-Verify-Edit)
 * 
 * For critical files:
 * 
 * 1. Read context: `grz_read_file("file.kt")` with appropriate offset/limit
 * 2. Verify old_string exists and is unique
 * 3. Execute edit: `grz_edit_file(...)`
 * 4. Optionally verify: `grz_read_file("file.kt")` to confirm change
 * 
 * # Transactionality
 * 
 * **Atomic file update:**
 * - File read, modified, written atomically
 * - Other readers see old content or new content (no partial states)
 * 
 * **No multi-edit transactions:**
 * - Each `grz_edit_file` call is independent
 * - If making multiple edits, some may succeed and others fail
 * - No rollback mechanism across edits
 * 
 * **Idempotency:**
 * - NOT idempotent (second call will fail with "old_string not found")
 * - Each successful edit changes file state
 * 
 * # Performance Characteristics
 * 
 * - **Small files (<10KB):** Instant edit
 * - **Medium files (10KB-100KB):** Very fast (milliseconds)
 * - **Large files (>100KB):** Fast (still faster than read+write pattern)
 * 
 * **Comparison to grz_write_file:**
 * - grz_edit_file: O(file size) - single scan and replace
 * - Read+Write pattern: O(file size × 2) + LLM context cost
 * 
 * **Recommendation:** Always use `grz_edit_file` for targeted changes in large files.
 * 
 * # Whitespace Handling
 * 
 * **Whitespace is significant:**
 * - Spaces, tabs, newlines must match exactly
 * - Mixing tabs and spaces will cause match failures
 * 
 * **Common pitfalls:**
 * - File uses tabs, old_string uses spaces → no match
 * - File has Windows line endings (CRLF), old_string has Unix (LF) → no match
 * - Trailing whitespace differences → no match
 * 
 * **Recommendation:**
 * - Copy exact text from `grz_read_file` output (preserves whitespace)
 * - Include line numbers from read output to ensure exact match
 * 
 * # Differences from grz_write_file
 * 
 * | Feature | grz_edit_file | grz_write_file |
 * |---------|---------------|----------------|
 * | Efficiency | Single scan & replace | Full file rewrite |
 * | Context needed | Only old_string | Entire file content |
 * | Safety | Preserves unmodified parts | Overwrites everything |
 * | Use case | Targeted edits | Creating/replacing files |
 * | LLM context cost | Low (just old/new strings) | High (full file content) |
 * | Risk | Low (only changes matched parts) | High (can break unrelated code) |
 * 
 * **Rule of thumb:**
 * - Changing <20% of file → use grz_edit_file
 * - Changing >20% of file → consider grz_write_file
 * - Creating new file → always use grz_write_file
 * 
 * # Limitations
 * 
 * **No regex support:**
 * - Only literal string matching
 * - For regex replacements, use `grz_execute_command` with `sed`
 * 
 * **No multi-line regex:**
 * - Can match multi-line strings, but only literal
 * - For complex patterns, use external tools
 * 
 * **No diff/patch format:**
 * - Requires exact old_string (not unified diff format)
 * - For applying patches, use `grz_execute_command` with `patch`
 * 
 * **No automatic formatting:**
 * - Doesn't reformat code after edit
 * - For formatted edits, run formatter after via `grz_execute_command`
 * 
 * # Related Tools
 * 
 * - **grz_read_file** - Read file to find old_string candidates
 * - **grz_write_file** - Replace entire file for large changes
 * - **grz_execute_command** with `sed` - Regex-based replacements
 * - **grz_execute_command** with `patch` - Apply unified diffs
 * 
 * # Infrastructure Implementation
 * 
 * See infrastructure-ai module for Spring AI integration:
 * @see com.gromozeka.infrastructure.ai.tool.GrzEditFileToolImpl
 * 
 * # Related Domain Services
 * 
 * This tool delegates to:
 * @see com.gromozeka.domain.service.FileSystemService.editFile (when created)
 */
interface GrzEditFileTool : Tool<EditFileRequest, Map<String, Any>> {
    
    override val name: String
        get() = "grz_edit_file"
    
    override val description: String
        get() = """
            Performs exact string replacements in files. 
            
            Key features:
            - Requires exact match of old_string (preserves indentation, whitespace)
            - Fails if old_string is not unique (use replace_all or provide more context)
            - Use replace_all for renaming variables/strings throughout file
            - More efficient than rewriting entire file with grz_write_file
        """.trimIndent()
    
    override val requestType: Class<EditFileRequest>
        get() = EditFileRequest::class.java
    
    override fun execute(request: EditFileRequest, context: ToolContext?): Map<String, Any>
}
