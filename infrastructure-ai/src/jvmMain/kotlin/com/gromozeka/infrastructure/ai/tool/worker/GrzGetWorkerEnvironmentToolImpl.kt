package com.gromozeka.infrastructure.ai.tool.worker

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkerEnvironmentProbe
import com.gromozeka.domain.service.WorkerEnvironmentSnapshot
import com.gromozeka.domain.service.WorkerWorkspaceStateService
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredProjectId
import com.gromozeka.domain.tool.requiredWorkerId
import com.gromozeka.domain.tool.worker.GetWorkerEnvironmentRequest
import com.gromozeka.domain.tool.worker.GrzGetWorkerEnvironmentTool
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzGetWorkerEnvironmentToolImpl(
    private val environmentProbe: WorkerEnvironmentProbe,
    private val workspaceStateService: WorkerWorkspaceStateService,
    @Value("\${gromozeka.runtime.worker.id}") configuredWorkerId: String,
) : GrzGetWorkerEnvironmentTool {
    private val localWorkerId = ConversationRuntimeWorkerId(configuredWorkerId.trim())

    override fun execute(
        request: GetWorkerEnvironmentRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any> {
        val projectId = context.requiredProjectId()
        val workerId = context.requiredWorkerId()
        check(workerId == localWorkerId) {
            "Worker environment request for ${workerId.value} reached ${localWorkerId.value}"
        }
        val mounts = runBlocking {
            workspaceStateService.findProjectMounts(projectId)
        }
        return environmentProbe.collectSnapshot(
            workspaceMounts = mounts,
            requestedExecutableNames = request.executable_names.toSet(),
        ).toToolResult(workerId.value)
    }
}

private fun WorkerEnvironmentSnapshot.toToolResult(workerId: String): Map<String, Any> =
    buildMap {
        put("worker_id", workerId)
        put("observed_at", profile.observedAt.toString())
        put(
            "operating_system",
            mapOf(
                "family" to profile.operatingSystem.family.name.lowercase(),
                "name" to profile.operatingSystem.name,
                "version" to profile.operatingSystem.version,
            ),
        )
        put("architecture", profile.architecture)
        put(
            "native_shell",
            mapOf(
                "kind" to profile.nativeShell.kind.name.lowercase(),
                "executable" to profile.nativeShell.executable,
            ),
        )
        put("timezone_id", profile.timezoneId)
        put("locale_tag", profile.localeTag)
        put("logical_processor_count", profile.logicalProcessorCount)
        profile.totalMemoryBytes?.let { put("total_memory_bytes", it) }
        put("available_executables", profile.availableExecutables)
        put("worker_process_uptime_ms", workerProcessUptimeMillis)
        availableMemoryBytes?.let { put("available_memory_bytes", it) }
        systemCpuLoadRatio?.let { put("system_cpu_load_ratio", it) }
        put(
            "requested_executables",
            requestedExecutables.map {
                mapOf(
                    "name" to it.name,
                    "available" to it.available,
                )
            },
        )
        put(
            "workspace_storage",
            workspaceStorage.map {
                buildMap<String, Any> {
                    put("workspace_mount_id", it.workspaceMountId.value)
                    put("root_path", it.rootPath)
                    put("accessible", it.accessible)
                    it.totalBytes?.let { bytes -> put("total_bytes", bytes) }
                    it.usableBytes?.let { bytes -> put("usable_bytes", bytes) }
                }
            },
        )
    }
