package com.gromozeka.application.service

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.RuntimeEnvironmentContext
import com.gromozeka.domain.model.RuntimeEnvironmentExecutor
import com.gromozeka.domain.model.Workspace
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class SystemPromptBuilderTest {
    private val builder = SystemPromptBuilder()
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `standalone environment does not invent project or workspace`() {
        val prompt = builder.buildEnvironmentInfo(
            RuntimeEnvironmentContext.Standalone(
                RuntimeEnvironmentExecutor.Worker("cloud-worker")
            ),
            includeCurrentTime = false,
        )

        assertContains(prompt, "Runtime executor: Worker cloud-worker")
        assertContains(prompt, "Runtime scope: standalone")
        assertFalse(prompt.contains("Workspace root path"))
        assertFalse(prompt.contains("Platform:"))
        assertFalse(prompt.contains("OS Version:"))
        assertFalse(prompt.contains("Current time:"))
    }

    @Test
    fun `workspace environment reports missing local mount explicitly`() {
        val project = Project(
            id = Project.Id("project-1"),
            name = "Project",
            createdAt = now,
            lastUsedAt = now,
        )
        val workspace = Workspace(
            id = Workspace.Id("workspace-1"),
            projectId = project.id,
            name = "Mac checkout",
            kind = Workspace.Kind.FILESYSTEM,
            createdAt = now,
            updatedAt = now,
        )

        val prompt = builder.buildEnvironmentInfo(
            RuntimeEnvironmentContext.WorkspaceBound(
                project = project,
                workspace = workspace,
                workerId = "cloud-worker",
                localMount = null,
            ),
            includeCurrentTime = false,
        )

        assertContains(prompt, "Project: Project (project-1)")
        assertContains(prompt, "Filesystem workspace: Mac checkout (workspace-1)")
        assertContains(prompt, "Workspace mounted on runtime worker: No")
        assertFalse(prompt.contains("Workspace root path"))
    }

    @Test
    fun `current time is explicit utc when enabled`() {
        val prompt = builder.buildEnvironmentInfo(
            RuntimeEnvironmentContext.Standalone(RuntimeEnvironmentExecutor.Server),
            includeCurrentTime = true,
        )

        assertContains(prompt, "Current time:")
        assertContains(prompt, "(timezone: UTC)")
    }
}
