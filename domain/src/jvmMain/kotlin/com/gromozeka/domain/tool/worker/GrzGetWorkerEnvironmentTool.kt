package com.gromozeka.domain.tool.worker

import com.gromozeka.domain.service.MAX_WORKER_ENVIRONMENT_EXECUTABLE_REQUESTS
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.ToolParameter
import com.gromozeka.domain.tool.WorkerInspectionToolMetadata

data class GetWorkerEnvironmentRequest(
    @property:ToolParameter(
        description = "Optional executable basenames to check in the worker PATH in addition to the standard advertised set.",
    )
    val executable_names: List<String> = emptyList(),
) {
    init {
        require(executable_names.size <= MAX_WORKER_ENVIRONMENT_EXECUTABLE_REQUESTS) {
            "At most $MAX_WORKER_ENVIRONMENT_EXECUTABLE_REQUESTS executable names may be requested"
        }
        require(executable_names.all { it == it.trim() && EXECUTABLE_NAME.matches(it) }) {
            "Executable names must be plain non-blank basenames"
        }
        require(executable_names.distinct().size == executable_names.size) {
            "Executable names must be unique"
        }
    }
}

interface GrzGetWorkerEnvironmentTool : Tool<GetWorkerEnvironmentRequest, Map<String, Any>> {
    override val name: String
        get() = "grz_get_worker_environment"

    override val metadata
        get() = WorkerInspectionToolMetadata

    override val description: String
        get() = """
            Reinspect the selected worker and return its complete current environment profile.
            Includes OS, architecture, native shell, timezone, locale, CPU and memory capacity, process uptime,
            current load, standard and explicitly requested executable availability, and storage for this project's
            workspace mounts on that worker. Use this when current worker facts matter instead of relying on the
            startup profile in the execution environment.
        """.trimIndent()

    override val requestType: Class<GetWorkerEnvironmentRequest>
        get() = GetWorkerEnvironmentRequest::class.java

    override fun execute(
        request: GetWorkerEnvironmentRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any>
}

private val EXECUTABLE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}")
