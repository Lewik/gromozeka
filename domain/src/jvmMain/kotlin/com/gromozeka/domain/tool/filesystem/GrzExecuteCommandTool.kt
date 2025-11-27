package com.gromozeka.domain.tool.filesystem

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for grz_execute_command tool.
 * 
 * @property command Shell command to execute
 * @property working_directory Working directory (optional, defaults to project root)
 * @property timeout_seconds Timeout in seconds (optional, default: 30)
 */
data class ExecuteCommandRequest(
    val command: String,
    val working_directory: String? = null,
    val timeout_seconds: Long? = null
)

/**
 * Domain specification for shell command execution tool.
 * 
 * # MCP Tool Exposure
 * 
 * **Tool name:** `grz_execute_command`
 * 
 * **Exposed via:** Spring AI + MCP protocol
 * 
 * # Purpose
 * 
 * Execute shell commands with timeout control and output capture. Provides full system
 * access for file operations, searches, builds, and automation tasks.
 * 
 * ⚠️ **SECURITY WARNING:**
 * This tool has **FULL SYSTEM ACCESS** - can execute any command with process permissions.
 * Use with caution in production environments. Consider sandboxing or command whitelisting.
 * 
 * ## Core Features
 * 
 * **Shell execution:**
 * - Runs commands via `sh -c` (Unix shell)
 * - Supports pipes, redirects, background jobs
 * - Environment variables inherited from process
 * - Working directory configurable
 * 
 * **Output capture:**
 * - STDOUT and STDERR merged into single output
 * - Returns complete output as string
 * - Exit code captured for success/failure detection
 * 
 * **Timeout protection:**
 * - Default 30 second timeout (prevents hanging)
 * - Configurable per command
 * - Forceful termination on timeout
 * 
 * # When to Use
 * 
 * **Use grz_execute_command when:**
 * - Searching file contents (ripgrep, grep)
 * - Finding files by name/pattern (find)
 * - Listing directory contents (ls, tree)
 * - Git operations (status, diff, log, commit)
 * - Build operations (gradle, maven, npm)
 * - File operations (cp, mv, rm, mkdir)
 * - Text processing (sed, awk, cut)
 * - System information (ps, df, du)
 * 
 * **Don't use when:**
 * - Reading single file → use `grz_read_file` (safer, better parsing)
 * - Writing single file → use `grz_write_file` (better error handling)
 * - Editing file → use `grz_edit_file` (more precise)
 * - Long-running processes → consider background execution patterns
 * 
 * # Usage Examples
 * 
 * ## Example 1: Search File Contents (ripgrep)
 * 
 * ```json
 * {
 *   "tool": "grz_execute_command",
 *   "parameters": {
 *     "command": "rg 'calculateTotal' --type kt -C 3"
 *   }
 * }
 * ```
 * 
 * ## Example 2: Find Files by Pattern
 * 
 * ```json
 * {
 *   "tool": "grz_execute_command",
 *   "parameters": {
 *     "command": "find . -name 'Test.kt' -type f"
 *   }
 * }
 * ```
 * 
 * ## Example 3: Git Status
 * 
 * ```json
 * {
 *   "tool": "grz_execute_command",
 *   "parameters": {
 *     "command": "git status --short"
 *   }
 * }
 * ```
 * 
 * ## Example 4: Build Project
 * 
 * ```json
 * {
 *   "tool": "grz_execute_command",
 *   "parameters": {
 *     "command": "./gradlew build",
 *     "timeout_seconds": 300
 *   }
 * }
 * ```
 * 
 * # Related Tools
 * 
 * - **grz_read_file** - Safer alternative for reading files
 * - **grz_write_file** - Better error handling for writing files
 * - **grz_edit_file** - Precise file modifications
 * 
 * # Infrastructure Implementation
 * 
 * See infrastructure-ai module for Spring AI integration:
 * @see com.gromozeka.infrastructure.ai.tool.GrzExecuteCommandToolImpl
 * 
 * # Related Domain Services
 * 
 * This tool delegates to:
 * @see com.gromozeka.domain.service.CommandExecutionService.execute (when created)
 */
interface GrzExecuteCommandTool : Tool<ExecuteCommandRequest, Map<String, Any>> {
    
    override val name: String
        get() = "grz_execute_command"
    
    override val description: String
        get() = """
            Execute shell command and return output. Use with caution - has full system access.
            
            Common use cases:
            - Search file contents: rg "pattern" --type kt -C 3
            - Find files by name: find . -name "*.kt" -type f
            - List directory: ls -la /path/to/dir
            - Git operations: git status, git diff, git log
            - Build operations: ./gradlew build, npm run build
        """.trimIndent()
    
    override val requestType: Class<ExecuteCommandRequest>
        get() = ExecuteCommandRequest::class.java
    
    override fun execute(request: ExecuteCommandRequest, context: ToolContext?): Map<String, Any>
}
