package com.gromozeka.domain.tool.filesystem

import com.gromozeka.domain.service.PythonProgram
import com.gromozeka.domain.service.requireValidCommandDescription
import com.gromozeka.domain.tool.AiToolContextField.AGENT
import com.gromozeka.domain.tool.AiToolContextField.CONVERSATION
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.PreloadedWorkspaceToolMetadata
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.ToolParameter
import com.gromozeka.domain.tool.withContextRequirements

const val GRZ_EXECUTE_PYTHON_TOOL_NAME = "grz_execute_python"

fun isManagedCommandLaunchTool(name: String): Boolean =
    name == GRZ_EXECUTE_COMMAND_TOOL_NAME || name == GRZ_EXECUTE_PYTHON_TOOL_NAME

data class ExecutePythonRequest(
    @property:ToolParameter(description = "Inline Python source. Supply exactly one of code or file_path; no preliminary file-writing call is needed.")
    val code: String? = null,
    @property:ToolParameter(description = "Existing Python file on the selected Worker, absolute or relative to working_directory. Mutually exclusive with code.")
    val file_path: String? = null,
    @property:ToolParameter(description = "Literal script arguments exposed as sys.argv[1:]. They are not interpreted by a shell. Do not include secrets.")
    val arguments: List<String> = emptyList(),
    @property:ToolParameter(description = "Working directory; defaults to the selected workspace root.")
    val working_directory: String? = null,
    @property:ToolParameter(
        description = "Initial wait before returning a WORKING task, not a script timeout.",
        minimum = 0,
        maximum = MAX_COMMAND_INITIAL_YIELD_MILLIS,
    )
    val yield_time_ms: Long = 10_000,
    @property:ToolParameter(description = "Optional hard execution timeout in seconds. Omit to run until completion or explicit cancellation.", minimum = 1)
    val timeout_seconds: Long? = null,
    @property:ToolParameter(description = "Allow the script to outlive the Worker process. Keep false unless continuity across a Worker restart is required.")
    val survive_worker_restart: Boolean = false,
    @property:ToolParameter(description = "Environment variable name to exact secret://name reference. Read values from os.environ; never print them or pass them in process arguments.")
    val secret_env: Map<String, String> = emptyMap(),
    @property:ToolParameter(description = "Short action label in the user's language, preferably at most 50 characters. Describe the purpose, not a claimed result. No secrets.")
    val description: String? = null,
) {
    init {
        program()
        requireValidCommandDescription(description)
    }

    fun program(): PythonProgram = PythonProgram(code, file_path, arguments.toList())
}

interface GrzExecutePythonTool : Tool<ExecutePythonRequest, List<AiToolResult>> {
    override val name: String get() = GRZ_EXECUTE_PYTHON_TOOL_NAME
    override val metadata
        get() = PreloadedWorkspaceToolMetadata.withContextRequirements(CONVERSATION, optional = setOf(AGENT))
    override val requestType: Class<ExecutePythonRequest> get() = ExecutePythonRequest::class.java

    override val description: String get() = """
        Run Python Code Mode on the selected desktop Worker using the bundled Python, standard library, HTTPX and async grz SDK. No installation or manual connection setup is needed. Use it for calculations, file/data processing, loops, branching, and composing multiple tool calls while keeping intermediate data out of model context. Direct tools remain appropriate for a single simple action.
        Supply exactly one source: code for an inline program, or file_path for an existing script. Inline code needs no write_file call. Each invocation starts a fresh managed process; variables are not shared between invocations. Files and other effects persist. arguments become sys.argv[1:]; working_directory defaults to the workspace root. Top-level await and async with are supported, as are ordinary scripts using asyncio.run.
        Import grz and use async with grz.Client.from_env() as client. Await client.list_tools(), client.describe_tool(exact_name), and client.call(exact_name, arguments). Discover actual names and inputSchema before calling unfamiliar tools; never invent version suffixes or arguments. The SDK exposes available built-in and external MCP tools and preserves the initiating user's and Agent's permissions.
        Discovery example:
            import grz
            async with grz.Client.from_env() as client:
                tools = await client.list_tools()
                print([t["name"] for t in tools if "read_file" in t["name"]])
        For previously discovered names and validated arguments, sequential dependencies use:
            first = await client.call(first_name, first_args)
            second = await client.call(second_name, arguments_derived_from(first))
        Independent calls use ordinary Python concurrency:
            import asyncio
            first, second = await asyncio.gather(client.call(first_name, first_args), client.call(second_name, second_args))
        These call examples belong inside the client's async with block. Process/filter results in Python and print only the useful output. call() returns the complete MCP result dict, including content and any structuredContent. Tool failures raise grz.ToolError with the complete result in error.result. PENDING/WORKING receipts acknowledge background work, not completion; follow that tool's task/request contract. No automatic retries, rollback, or cancellation of already-dispatched remote work is performed.
        This is not a security sandbox: the script has the same OS access as other managed commands. Use secret_env for named credentials; read os.environ directly and do not dump the environment or credentials.
        Short scripts return a terminal result. Longer scripts return WORKING and task_id after yield_time_ms, with conversation completion delivered automatically. Use grz_get_command_task for intermediate output, grz_monitor_command for output events, grz_send_command_input for stdin, and grz_cancel_command_task to terminate the local process tree. Do not poll merely to await completion in a conversation. External MCP clients without conversation delivery must inspect task status explicitly. Full stdout/stderr are retained as managed output; responses contain a bounded chunk and an artifact. Errors and nonzero exits are failures, not successful results. Do not start unmanaged background processes.
    """.trimIndent()

    override fun execute(request: ExecutePythonRequest, context: ToolExecutionContext?): List<AiToolResult>
}
