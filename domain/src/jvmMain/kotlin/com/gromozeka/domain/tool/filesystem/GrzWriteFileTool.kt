package com.gromozeka.domain.tool.filesystem

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for grz_write_file tool.
 * 
 * @property file_path Path to the file (absolute or relative to project root)
 * @property content Content to write to the file
 */
data class WriteFileRequest(
    val file_path: String,
    val content: String
)

/**
 * Domain specification for file writing tool.
 * 
 * # MCP Tool Exposure
 * 
 * **Tool name:** `grz_write_file`
 * 
 * **Exposed via:** Spring AI + MCP protocol
 * 
 * # Purpose
 * 
 * Write content to a file with automatic parent directory creation. Designed for creating
 * new files or completely replacing existing file contents.
 * 
 * ## Core Features
 * 
 * **Automatic directory creation:**
 * - Creates parent directories if they don't exist
 * - Uses `File.mkdirs()` for recursive creation
 * - No error if directories already exist
 * 
 * **Overwrite behavior:**
 * - ⚠️ **OVERWRITES existing files WITHOUT confirmation**
 * - No backup created
 * - No undo capability
 * - Use with caution on existing files
 * 
 * **Content handling:**
 * - Writes exact content (no formatting changes)
 * - Handles any text encoding (UTF-8 default)
 * - Can write empty files (content="")
 * - Preserves line endings from content string
 * 
 * # When to Use
 * 
 * **Use grz_write_file when:**
 * - Creating new files
 * - Completely replacing file contents
 * - Writing configuration files
 * - Generating code files
 * - Creating documentation
 * - Setting up project structure
 * 
 * **Don't use when:**
 * - Need to modify part of existing file → use `grz_edit_file` instead
 * - Need to append to file → use `grz_execute_command` with `>>` redirect
 * - Need to preserve existing content → use `grz_read_file` then `grz_write_file`
 * - Need backup before overwrite → use `grz_execute_command` with `cp` first
 * 
 * # Parameters
 * 
 * ## file_path: String (required)
 * 
 * Path to the file to write. Can be absolute or relative to project root.
 * 
 * **Examples:**
 * - `"src/main/kotlin/NewFile.kt"` - Create in package structure
 * - `"/tmp/output.txt"` - Absolute path
 * - `"./README.md"` - Current directory
 * - `"config/settings.json"` - Nested in new directory
 * 
 * **Path resolution:**
 * - Relative paths resolved against project root (from ToolContext)
 * - Absolute paths used as-is
 * - Parent directories created automatically
 * 
 * ## content: String (required)
 * 
 * Content to write to the file.
 * 
 * **Content handling:**
 * - Exact string written (no modifications)
 * - Empty string creates empty file
 * - Line endings preserved as-is
 * - No automatic formatting or indentation
 * 
 * **Size considerations:**
 * - No built-in size limit
 * - Large content (>1MB) may be slow
 * - Consider streaming for very large files
 * 
 * # Returns
 * 
 * Returns `Map<String, Any>` with execution result:
 * 
 * ## Success Response
 * 
 * ```json
 * {
 *   "success": true,
 *   "path": "/absolute/path/to/file.txt",
 *   "bytes": 1234
 * }
 * ```
 * 
 * **Fields:**
 * - `success` - Always `true` on success
 * - `path` - Absolute path to written file
 * - `bytes` - Number of bytes written (UTF-8 encoding)
 * 
 * ## Error Response
 * 
 * ```json
 * {
 *   "error": "Error writing file: Permission denied"
 * }
 * ```
 * 
 * **Error message includes:**
 * - Root cause (IOException details)
 * - Original file path (for context)
 * 
 * # Error Cases
 * 
 * | Error | Reason | Solution |
 * |-------|--------|----------|
 * | Permission denied | No write access to path | Check permissions, use different path |
 * | Read-only filesystem | Mounted as read-only | Choose writable location |
 * | Disk full | No space left on device | Free up space, use different disk |
 * | Invalid path | Path contains illegal chars | Use valid path characters |
 * | Path is directory | Trying to overwrite directory | Use different path or delete directory |
 * 
 * # Usage Examples
 * 
 * ## Example 1: Create New File
 * 
 * **Use case:** Create a new Kotlin source file
 * 
 * ```json
 * {
 *   "tool": "grz_write_file",
 *   "parameters": {
 *     "file_path": "src/main/kotlin/com/example/NewService.kt",
 *     "content": "package com.example\n\nclass NewService {\n    fun doSomething() {\n        println(\"Hello\")\n    }\n}\n"
 *   }
 * }
 * ```
 * 
 * **Result:** File created with parent directories
 * 
 * ## Example 2: Write Configuration File
 * 
 * **Use case:** Create application config
 * 
 * ```json
 * {
 *   "tool": "grz_write_file",
 *   "parameters": {
 *     "file_path": "config/application.yaml",
 *     "content": "server:\n  port: 8080\n  host: localhost\n"
 *   }
 * }
 * ```
 * 
 * **Result:** Config directory and file created
 * 
 * ## Example 3: Overwrite Existing File
 * 
 * **Use case:** Completely replace file contents
 * 
 * ```json
 * {
 *   "tool": "grz_write_file",
 *   "parameters": {
 *     "file_path": "README.md",
 *     "content": "# Updated Project\n\nThis is the new README content.\n"
 *   }
 * }
 * ```
 * 
 * **Result:** Old README.md completely replaced (no backup)
 * 
 * ## Example 4: Create Empty File
 * 
 * **Use case:** Create placeholder or lock file
 * 
 * ```json
 * {
 *   "tool": "grz_write_file",
 *   "parameters": {
 *     "file_path": ".gitkeep",
 *     "content": ""
 *   }
 * }
 * ```
 * 
 * **Result:** Empty file created
 * 
 * ## Example 5: Write JSON Configuration
 * 
 * **Use case:** Generate structured config file
 * 
 * ```json
 * {
 *   "tool": "grz_write_file",
 *   "parameters": {
 *     "file_path": "settings.json",
 *     "content": "{\n  \"debug\": true,\n  \"timeout\": 5000\n}"
 *   }
 * }
 * ```
 * 
 * **Result:** Formatted JSON file created
 * 
 * # Common Patterns
 * 
 * ## Pattern: Safe Overwrite (Read-Modify-Write)
 * 
 * When modifying existing files:
 * 
 * 1. Read current content: `grz_read_file("file.txt", limit=-1)`
 * 2. Modify content in LLM
 * 3. Write back: `grz_write_file("file.txt", modified_content)`
 * 
 * **Note:** Consider using `grz_edit_file` for targeted changes instead.
 * 
 * ## Pattern: Backup Before Overwrite
 * 
 * If file is critical:
 * 
 * 1. Copy original: `grz_execute_command("cp file.txt file.txt.backup")`
 * 2. Write new version: `grz_write_file("file.txt", new_content)`
 * 3. Verify: `grz_read_file("file.txt")`
 * 
 * ## Pattern: Batch File Creation
 * 
 * Create multiple files in sequence:
 * 
 * ```kotlin
 * grz_write_file("src/A.kt", contentA)
 * grz_write_file("src/B.kt", contentB)
 * grz_write_file("test/ATest.kt", testA)
 * ```
 * 
 * **Note:** Each call is independent, no transaction spanning multiple files.
 * 
 * # Transactionality
 * 
 * **Atomic file write:**
 * - File written completely or not at all
 * - No partial writes visible to other readers
 * - Java's `File.writeText()` handles atomicity
 * 
 * **No multi-file transactions:**
 * - Each `grz_write_file` call is independent
 * - If writing multiple files, some may succeed and others fail
 * - No rollback mechanism
 * 
 * **Concurrency:**
 * - Not safe for concurrent writes to same file
 * - Last write wins (no file locking)
 * - Use external locking for concurrent access
 * 
 * # Performance Characteristics
 * 
 * - **Small files (<100KB):** Instant write
 * - **Medium files (100KB-1MB):** Fast (milliseconds)
 * - **Large files (>1MB):** Slower (seconds), consider chunking
 * - **SSD vs HDD:** SSD significantly faster for small files
 * 
 * **Directory creation overhead:**
 * - First write to new directory slower (mkdir)
 * - Subsequent writes in same directory fast
 * 
 * # Security Considerations
 * 
 * **Path traversal risk:**
 * - ⚠️ Tool can write to ANY path accessible to process
 * - No sandboxing or path restrictions
 * - Resolved against project root, but `../` can escape
 * 
 * **Recommendation for production:**
 * - Validate paths in domain service
 * - Restrict to project directory tree
 * - Whitelist allowed directories
 * 
 * **File permissions:**
 * - Created files inherit process umask
 * - No explicit permission setting
 * - May need `grz_execute_command("chmod")` for specific permissions
 * 
 * # Differences from grz_edit_file
 * 
 * | Feature | grz_write_file | grz_edit_file |
 * |---------|----------------|---------------|
 * | Use case | Create/replace entire file | Modify specific parts |
 * | Efficiency | Full file rewrite | Targeted replacement |
 * | Safety | Overwrites everything | Preserves unmodified parts |
 * | Requires read | No | Yes (to find old_string) |
 * | Multiple changes | One write call | Multiple edits possible |
 * 
 * **When to use grz_edit_file instead:**
 * - Changing single function in large file
 * - Renaming variables/symbols
 * - Updating imports or dependencies
 * - Preserving most of existing content
 * 
 * # Related Tools
 * 
 * - **grz_read_file** - Read file contents (often used before write)
 * - **grz_edit_file** - Targeted modifications without full rewrite
 * - **grz_execute_command** - Alternative for appending, copying, moving files
 * 
 * # Infrastructure Implementation
 * 
 * See infrastructure-ai module for Spring AI integration:
 * @see com.gromozeka.infrastructure.ai.tool.GrzWriteFileToolImpl
 * 
 * # Related Domain Services
 * 
 * This tool delegates to:
 * @see com.gromozeka.domain.service.FileSystemService.writeFile (when created)
 */
interface GrzWriteFileTool : Tool<WriteFileRequest, Map<String, Any>> {
    
    override val name: String
        get() = "grz_write_file"
    
    override val description: String
        get() = "Write content to a file. Creates parent directories if needed. Overwrites existing files."
    
    override val requestType: Class<WriteFileRequest>
        get() = WriteFileRequest::class.java
    
    override fun execute(request: WriteFileRequest, context: ToolContext?): Map<String, Any>
}
