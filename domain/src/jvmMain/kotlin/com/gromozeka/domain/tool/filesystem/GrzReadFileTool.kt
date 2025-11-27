package com.gromozeka.domain.tool.filesystem

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for grz_read_file tool.
 * 
 * @property file_path Path to the file (absolute or relative to project root)
 * @property limit Maximum lines to read (default: 1000 for safety, -1 for entire file)
 * @property offset Skip first N lines (default: 0, useful for pagination)
 */
data class ReadFileRequest(
    val file_path: String,
    val limit: Int = 1000,
    val offset: Int = 0
)

/**
 * Domain specification for file reading tool.
 * 
 * # MCP Tool Exposure
 * 
 * **Tool name:** `grz_read_file`
 * 
 * **Exposed via:** Spring AI + MCP protocol
 * 
 * # Purpose
 * 
 * Read file contents with built-in safety limits and rich metadata. Designed to prevent
 * accidental loading of large files while providing flexible access patterns for LLMs.
 * 
 * ## Core Features
 * 
 * **Safety-first defaults:**
 * - Default reads first 1000 lines (prevents context overflow)
 * - Explicit `-1` required for full file read (AI-native pattern)
 * - Metadata shows total lines and what was read
 * 
 * **Multiple content types:**
 * - Text files: Line-numbered content with truncation
 * - Images: Base64 encoded (PNG, JPEG, GIF, WebP)
 * - PDFs: Base64 encoded document
 * 
 * **Flexible access patterns:**
 * - Preview (first 1000 lines)
 * - Full read (limit=-1)
 * - Pagination (limit + offset)
 * - Size check (limit=1, read metadata only)
 * 
 * # When to Use
 * 
 * **Use grz_read_file when:**
 * - Need to inspect file contents
 * - Analyzing code structure
 * - Reading configuration files
 * - Checking file size before full read
 * - Paginating through large files
 * - Loading images for visual analysis
 * 
 * **Don't use when:**
 * - Need to search across multiple files → use `grz_execute_command` with `rg`
 * - Need to list directory contents → use `grz_execute_command` with `ls`
 * - File doesn't exist yet → use `grz_write_file` to create
 * 
 * # Parameters
 * 
 * ## file_path: String (required)
 * 
 * Path to the file to read. Can be absolute or relative to project root.
 * 
 * **Examples:**
 * - `"src/main/kotlin/Main.kt"` - Relative path
 * - `"/absolute/path/to/file.txt"` - Absolute path
 * - `"./README.md"` - Current directory
 * - `"../sibling-project/config.json"` - Parent directory
 * 
 * **Validation:**
 * - Path must point to existing file (not directory)
 * - Returns error if file not found
 * - Returns error if path is directory
 * 
 * ## limit: Int (optional, default: 1000)
 * 
 * Maximum number of lines to read.
 * 
 * **Special values:**
 * - `1000` (default) - Safe preview, prevents accidental large loads
 * - `-1` - Read entire file (explicit intent required)
 * - `> 0` - Custom limit for specific use cases
 * 
 * **Why default to 1000?**
 * - Balances preview quality with safety
 * - Fits comfortably in typical LLM context windows
 * - Prevents accidental loading of huge log files
 * - Forces explicit choice for large files
 * 
 * ## offset: Int (optional, default: 0)
 * 
 * Number of lines to skip from the start.
 * 
 * **Use cases:**
 * - Pagination: Read chunks of large files
 * - Skip headers: Start from line N
 * - Resume reading: Continue from previous position
 * 
 * # Returns
 * 
 * Returns `Map<String, Any>` with different structures based on content type:
 * 
 * ## Text Files
 * 
 * ```json
 * {
 *   "type": "text",
 *   "text": "1\tFirst line content\n2\tSecond line content\n...\n[Read lines 1-1000 of 5000 total] (more lines available)"
 * }
 * ```
 * 
 * **Format details:**
 * - Each line prefixed with line number and tab
 * - Lines longer than 2000 chars truncated with "..."
 * - Metadata appended: `[Read lines X-Y of Z total]`
 * - If more lines available: `(more lines available)` suffix
 * 
 * ## Image Files (PNG, JPEG, GIF, WebP)
 * 
 * ```json
 * {
 *   "type": "text",
 *   "text": "Successfully read image: screenshot.png (45678 bytes, image/png)",
 *   "additionalContent": [
 *     {
 *       "type": "image",
 *       "source": {
 *         "type": "base64",
 *         "media_type": "image/png",
 *         "data": "iVBORw0KGgo..."
 *       }
 *     }
 *   ]
 * }
 * ```
 * 
 * ## PDF Files
 * 
 * ```json
 * {
 *   "type": "document",
 *   "source": {
 *     "type": "base64",
 *     "media_type": "application/pdf",
 *     "data": "JVBERi0xLj..."
 *   }
 * }
 * ```
 * 
 * ## Error Response
 * 
 * ```json
 * {
 *   "error": "File not found: src/Missing.kt"
 * }
 * ```
 * 
 * # Error Cases
 * 
 * | Error | Reason | Solution |
 * |-------|--------|----------|
 * | File not found | Path doesn't exist | Check path, use correct relative/absolute |
 * | Path is not a file | Path points to directory | Use grz_execute_command with `ls` instead |
 * | Limit must be positive or -1 | Invalid limit value (e.g., 0, -2) | Use limit=1000 or limit=-1 |
 * | Error reading file | IO exception, permission denied | Check file permissions, disk space |
 * 
 * # Usage Examples
 * 
 * ## Example 1: Safe Preview (Default Behavior)
 * 
 * **Use case:** Check what's in a file without loading everything
 * 
 * ```json
 * {
 *   "tool": "grz_read_file",
 *   "parameters": {
 *     "file_path": "src/Main.kt"
 *   }
 * }
 * ```
 * 
 * **Result:** First 1000 lines with metadata showing total size
 * 
 * ## Example 2: Read Entire Small File
 * 
 * **Use case:** Load complete config file
 * 
 * ```json
 * {
 *   "tool": "grz_read_file",
 *   "parameters": {
 *     "file_path": "application.yaml",
 *     "limit": -1
 *   }
 * }
 * ```
 * 
 * **Result:** Complete file contents (explicit intent via -1)
 * 
 * ## Example 3: Check File Size Before Reading
 * 
 * **Use case:** Decide if file is safe to load fully
 * 
 * ```json
 * {
 *   "tool": "grz_read_file",
 *   "parameters": {
 *     "file_path": "logs/app.log",
 *     "limit": 1
 *   }
 * }
 * ```
 * 
 * **Result:** Single line + metadata: `[Read lines 1-1 of 50000 total]`
 * → Now you know it's 50k lines, decide how to proceed
 * 
 * ## Example 4: Paginate Large File
 * 
 * **Use case:** Read log file in chunks
 * 
 * ```json
 * {
 *   "tool": "grz_read_file",
 *   "parameters": {
 *     "file_path": "build.log",
 *     "limit": 100,
 *     "offset": 1000
 *   }
 * }
 * ```
 * 
 * **Result:** Lines 1001-1100 of total
 * 
 * ## Example 5: Read Image
 * 
 * **Use case:** Analyze screenshot or diagram
 * 
 * ```json
 * {
 *   "tool": "grz_read_file",
 *   "parameters": {
 *     "file_path": "docs/architecture.png"
 *   }
 * }
 * ```
 * 
 * **Result:** Base64 encoded image in additionalContent
 * 
 * # Common Patterns
 * 
 * ## Pattern: Incremental File Analysis
 * 
 * 1. Check size: `grz_read_file("file.txt", limit=1)`
 * 2. If small (<1000 lines): `grz_read_file("file.txt", limit=-1)`
 * 3. If large: Read in chunks with offset
 * 
 * ## Pattern: Safe Exploration
 * 
 * 1. Preview: `grz_read_file("unknown.txt")` (default limit)
 * 2. Assess content (is it what you need?)
 * 3. Load fully: `grz_read_file("unknown.txt", limit=-1)`
 * 
 * ## Pattern: Targeted Reading
 * 
 * 1. Find line range: `grz_execute_command("rg -n 'pattern' file.txt")`
 * 2. Read specific range: `grz_read_file("file.txt", offset=100, limit=50)`
 * 
 * # Transactionality
 * 
 * **Read-only operation** - No side effects, safe to call multiple times.
 * 
 * **Concurrency:** Safe for concurrent reads (file system handles locking).
 * 
 * **Idempotency:** Yes - same input always produces same output (assuming file unchanged).
 * 
 * # Performance Characteristics
 * 
 * - **Small files (<1000 lines):** Instant read
 * - **Large files with limit:** Fast (reads only requested lines)
 * - **Full read of huge file:** Slow (reads entire file into memory)
 * - **Images/PDFs:** Base64 encoding adds overhead (~33% size increase)
 * 
 * **Recommendation:** Always use default limit for unknown files, only use -1 when
 * you know file is small or you actually need complete content.
 * 
 * # Related Tools
 * 
 * - **grz_write_file** - Write/overwrite file contents
 * - **grz_edit_file** - Modify specific parts without full rewrite
 * - **grz_execute_command** with `rg` - Search file contents
 * - **grz_execute_command** with `ls` - List directory contents
 * 
 * # Infrastructure Implementation
 * 
 * See infrastructure-ai module for Spring AI integration:
 * @see com.gromozeka.infrastructure.ai.tool.GrzReadFileToolImpl
 * 
 * # Related Domain Services
 * 
 * This tool delegates to:
 * @see com.gromozeka.domain.service.FileSystemService.readFile (when created)
 */
interface GrzReadFileTool : Tool<ReadFileRequest, Map<String, Any>> {
    
    override val name: String
        get() = "grz_read_file"
    
    override val description: String
        get() = """
            Read file contents with safety limits and metadata.
            
            Parameters:
            - file_path: Path to the file (required)
            - limit: Max lines to read (optional, default: 1000 for safety, use -1 for entire file)
            - offset: Skip first N lines (optional, default: 0)
            
            Safety & Control:
            - Default reads first 1000 lines (prevents accidental large file loads)
            - Use limit=-1 to explicitly read entire file (when you know it's safe)
            - Returns metadata showing total lines and what was read
            
            Common patterns:
            - Preview file safely: grz_read_file("file.txt")  // First 1000 lines
            - Read entire small file: grz_read_file("config.json", limit=-1)
            - Check file size: grz_read_file("huge.log", limit=1)  // See total lines in metadata
            - Paginate large file: grz_read_file("data.csv", limit=100, offset=1000)
            
            Returns:
            - Content with line numbers (e.g., "1\tline content")
            - Metadata: [Read lines X-Y of Z total] (more lines available)
            - Images/PDFs: Base64 encoded
        """.trimIndent()
    
    override val requestType: Class<ReadFileRequest>
        get() = ReadFileRequest::class.java
    
    override fun execute(request: ReadFileRequest, context: ToolContext?): Map<String, Any>
}
