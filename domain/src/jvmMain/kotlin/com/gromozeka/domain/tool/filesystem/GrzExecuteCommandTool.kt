package com.gromozeka.domain.tool.filesystem

import com.gromozeka.domain.tool.LocalAgentToolMetadata
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.ToolParameter

const val MAX_COMMAND_INITIAL_YIELD_MILLIS = 30_000L

/**
 * Request parameters for grz_execute_command tool.
 * 
 * @property command Native shell command to execute
 * @property working_directory Working directory (optional, defaults to workspace root)
 * @property yield_time_ms Time to wait before returning a running task
 * @property timeout_seconds Optional hard timeout in seconds
 */
data class ExecuteCommandRequest(
    val command: String,
    val working_directory: String? = null,
    @property:ToolParameter(
        description = "Initial wait before returning a WORKING task. This is not the command timeout.",
        minimum = 0,
        maximum = MAX_COMMAND_INITIAL_YIELD_MILLIS,
    )
    val yield_time_ms: Long = 10_000,
    @property:ToolParameter(
        description = "Hard command timeout in seconds. Omit to allow the command to run until completion or explicit cancellation.",
        minimum = 1,
    )
    val timeout_seconds: Long? = null,
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
 * - Runs commands via `/bin/sh -c` on macOS/Linux
 * - Runs commands via `cmd.exe` on Windows
 * - Supports pipes, redirects, and compound commands
 * - Environment variables inherited from process
 * - Working directory configurable
 * 
 * **Output capture:**
 * - STDOUT and STDERR merged into one stream
 * - Full output is saved to a file under Gromozeka home
 * - Returned `output` is a bounded chunk with a byte cursor
 * - Exit code captured for success/failure detection
 * 
 * **Task lifecycle:**
 * - Waits briefly for short commands
 * - Returns a task ID when a command keeps running
 * - Supports incremental output reads and explicit process-tree cancellation
 * - Applies a hard timeout only when timeout_seconds is provided
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
 * - Interactive terminal applications that require a TTY
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
 * @see com.gromozeka.domain.service.CommandTaskService
 */
interface GrzExecuteCommandTool : Tool<ExecuteCommandRequest, Map<String, Any>> {
    
    override val name: String
        get() = "grz_execute_command"

    override val metadata
        get() = LocalAgentToolMetadata
    
    override val description: String
        get() = """
            Execute a shell command as a managed task. Short commands return a terminal result; long commands return status WORKING and task_id after yield_time_ms.
            The target Worker uses its native shell: /bin/sh on macOS/Linux and cmd.exe on Windows. Match command syntax to the target WorkspaceMount and Worker.
            yield_time_ms is only the initial wait and may be from 0 to $MAX_COMMAND_INITIAL_YIELD_MILLIS; it is not the command timeout.
            Use grz_get_command_task with next_output_byte until the task is terminal and has_more_output is false. Use grz_cancel_command_task to terminate the process tree.
            Do not create unmanaged background processes with '&' on macOS/Linux or 'start' without '/WAIT' on Windows. Full merged stdout/stderr is saved to output_file.
            Use with caution - has full system access.
            
            Common use cases:
            - Search file contents: rg "pattern" --type kt -C 3
            - Find files by name: find . -name "*.kt" -type f
            - List directory: ls -la /path/to/dir
            - Git operations: git status, git diff, git log
            - Build operations: ./gradlew build, npm run build
            Large output is chunked; keep calling grz_get_command_task with next_output_byte or rerun a narrower command when needed.
        """.trimIndent()
    
    override val requestType: Class<ExecuteCommandRequest>
        get() = ExecuteCommandRequest::class.java
    
    override fun execute(request: ExecuteCommandRequest, context: ToolExecutionContext?): Map<String, Any>
}
