package com.gromozeka.infrastructure.ai.tool.worker

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceExecutionContext
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.WorkerEnvironmentProbe
import com.gromozeka.domain.service.WorkerEnvironmentProfile
import com.gromozeka.domain.service.WorkerEnvironmentSnapshot
import com.gromozeka.domain.service.WorkerExecutableAvailability
import com.gromozeka.domain.service.WorkerNativeShell
import com.gromozeka.domain.service.WorkerOperatingSystem
import com.gromozeka.domain.service.WorkerWorkspaceStateService
import com.gromozeka.domain.service.WorkerWorkspaceStorage
import com.gromozeka.domain.tool.TOOL_CONTEXT_PROJECT_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_WORKER_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.worker.GetWorkerEnvironmentRequest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GrzGetWorkerEnvironmentToolImplTest {
    private val observedAt = Instant.parse("2026-07-28T00:00:00Z")
    private val project = Project.Id("project-a")
    private val foreignProject = Project.Id("project-b")
    private val workspace = workspace("workspace-a", project)
    private val foreignWorkspace = workspace("workspace-b", foreignProject)
    private val matchingMount = mount("mount-a", workspace.id, "worker-a", "/workspace/a")
    private val foreignMount = mount("mount-foreign", foreignWorkspace.id, "worker-a", "/workspace/foreign")

    @Test
    fun `reinspects selected worker with only current project mounts`() {
        val probe = RecordingProbe()
        val tool = GrzGetWorkerEnvironmentToolImpl(
            environmentProbe = probe,
            workspaceStateService = FakeWorkerWorkspaceStateService(
                mountsByProject = mapOf(
                    project to listOf(matchingMount),
                    foreignProject to listOf(foreignMount),
                ),
            ),
            configuredWorkerId = "worker-a",
        )

        val result = tool.execute(
            request = GetWorkerEnvironmentRequest(listOf("jq", "python3")),
            context = context(project, "worker-a"),
        )

        assertEquals(listOf(matchingMount), probe.workspaceMounts)
        assertEquals(setOf("jq", "python3"), probe.requestedExecutableNames)
        assertEquals("worker-a", result["worker_id"])
        assertEquals("arm64", result["architecture"])
        assertEquals(
            mapOf("family" to "macos", "name" to "macOS", "version" to "26.0"),
            result["operating_system"],
        )
        assertEquals(
            mapOf("kind" to "posix_sh", "executable" to "/bin/sh"),
            result["native_shell"],
        )
        assertEquals("Asia/Jerusalem", result["timezone_id"])
        assertEquals("en-IL", result["locale_tag"])
        assertEquals(10, result["logical_processor_count"])
        assertEquals(32_000_000_000, result["total_memory_bytes"])
        assertEquals(listOf("git", "jq"), result["available_executables"])
        assertEquals(12_345L, result["worker_process_uptime_ms"])
        assertEquals(16_000_000_000, result["available_memory_bytes"])
        assertEquals(0.25, result["system_cpu_load_ratio"])
        assertEquals(
            listOf(
                mapOf("name" to "jq", "available" to true),
                mapOf("name" to "python3", "available" to false),
            ),
            result["requested_executables"],
        )
        assertEquals(
            listOf(
                mapOf(
                    "workspace_mount_id" to matchingMount.id.value,
                    "root_path" to matchingMount.rootPath,
                    "accessible" to true,
                    "total_bytes" to 1_000L,
                    "usable_bytes" to 400L,
                )
            ),
            result["workspace_storage"],
        )
    }

    @Test
    fun `rejects request delivered to a different worker`() {
        val tool = GrzGetWorkerEnvironmentToolImpl(
            environmentProbe = RecordingProbe(),
            workspaceStateService = FakeWorkerWorkspaceStateService(emptyMap()),
            configuredWorkerId = "worker-a",
        )

        assertFailsWith<IllegalStateException> {
            tool.execute(GetWorkerEnvironmentRequest(), context(project, "worker-b"))
        }
    }

    @Test
    fun `request rejects paths duplicates and oversized executable lists`() {
        assertFailsWith<IllegalArgumentException> {
            GetWorkerEnvironmentRequest(listOf("/usr/bin/git"))
        }
        assertFailsWith<IllegalArgumentException> {
            GetWorkerEnvironmentRequest(listOf("git", "git"))
        }
        assertFailsWith<IllegalArgumentException> {
            GetWorkerEnvironmentRequest((0..32).map { "tool-$it" })
        }
    }

    private fun context(
        projectId: Project.Id,
        workerId: String,
    ): ToolExecutionContext =
        ToolExecutionContext(
            mapOf(
                TOOL_CONTEXT_PROJECT_ID to projectId.value,
                TOOL_CONTEXT_WORKER_ID to workerId,
            )
        )

    private fun workspace(
        id: String,
        projectId: Project.Id,
    ): Workspace =
        Workspace(
            id = Workspace.Id(id),
            projectId = projectId,
            name = id,
            kind = Workspace.Kind.FILESYSTEM,
            createdAt = observedAt,
            updatedAt = observedAt,
        )

    private fun mount(
        id: String,
        workspaceId: Workspace.Id,
        workerId: String,
        rootPath: String,
    ): WorkspaceMount =
        WorkspaceMount(
            id = WorkspaceMount.Id(id),
            workspaceId = workspaceId,
            workerId = workerId,
            rootPath = rootPath,
            createdAt = observedAt,
            updatedAt = observedAt,
        )

    private fun profile(): WorkerEnvironmentProfile =
        WorkerEnvironmentProfile(
            observedAt = observedAt,
            operatingSystem = WorkerOperatingSystem(
                family = WorkerOperatingSystem.Family.MACOS,
                name = "macOS",
                version = "26.0",
            ),
            architecture = "arm64",
            nativeShell = WorkerNativeShell(WorkerNativeShell.Kind.POSIX_SH, "/bin/sh"),
            timezoneId = "Asia/Jerusalem",
            localeTag = "en-IL",
            logicalProcessorCount = 10,
            totalMemoryBytes = 32_000_000_000,
            availableExecutables = listOf("git", "jq"),
        )

    private inner class RecordingProbe : WorkerEnvironmentProbe {
        var workspaceMounts: List<WorkspaceMount> = emptyList()
        var requestedExecutableNames: Set<String> = emptySet()

        override fun collectProfile(): WorkerEnvironmentProfile = profile()

        override fun collectSnapshot(
            workspaceMounts: List<WorkspaceMount>,
            requestedExecutableNames: Set<String>,
        ): WorkerEnvironmentSnapshot {
            this.workspaceMounts = workspaceMounts
            this.requestedExecutableNames = requestedExecutableNames
            return WorkerEnvironmentSnapshot(
                profile = profile(),
                workerProcessUptimeMillis = 12_345,
                availableMemoryBytes = 16_000_000_000,
                systemCpuLoadRatio = 0.25,
                requestedExecutables = requestedExecutableNames.sorted().map {
                    WorkerExecutableAvailability(it, it == "jq")
                },
                workspaceStorage = workspaceMounts.map {
                    WorkerWorkspaceStorage(
                        workspaceMountId = it.id,
                        rootPath = it.rootPath,
                        accessible = true,
                        totalBytes = 1_000,
                        usableBytes = 400,
                    )
                },
            )
        }
    }

    private class FakeWorkerWorkspaceStateService(
        private val mountsByProject: Map<Project.Id, List<WorkspaceMount>>,
    ) : WorkerWorkspaceStateService {
        override suspend fun createAndMountFilesystemWorkspace(
            projectId: Project.Id,
            name: String,
            rootPath: String,
        ): WorkspaceExecutionContext = error("Not used")

        override suspend fun attachFilesystemWorkspace(
            projectId: Project.Id,
            workspaceId: Workspace.Id,
            rootPath: String,
        ): WorkspaceExecutionContext = error("Not used")

        override suspend fun findProjectMounts(projectId: Project.Id): List<WorkspaceMount> =
            mountsByProject[projectId].orEmpty()
    }
}
