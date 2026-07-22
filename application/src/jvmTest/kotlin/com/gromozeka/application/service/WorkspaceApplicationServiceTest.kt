package com.gromozeka.application.service

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.RuntimeEnvironmentContext
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.repository.ProjectRepository
import com.gromozeka.domain.repository.WorkspaceRepository
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class WorkspaceApplicationServiceTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val project = Project(
        id = Project.Id("project-1"),
        name = "Project",
        createdAt = now,
        lastUsedAt = now,
    )
    private val workspace = Workspace(
        id = Workspace.Id("workspace-1"),
        projectId = project.id,
        name = "Mac checkout",
        kind = Workspace.Kind.FILESYSTEM,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `runtime context does not require workspace mount on current worker`() = runBlocking {
        val service = WorkspaceApplicationService(
            projectRepository = TestProjectRepository(project),
            workspaceRepository = TestWorkspaceRepository(workspace),
        )

        val context = service.resolveRuntime(workspace.id, "cloud-llm")

        assertEquals(project, context.project)
        assertEquals(workspace, context.workspace)
        assertEquals("cloud-llm", context.workerId)
        assertNull(context.localMount)
        assertNull(context.workspaceRootPath)
        assertEquals(RuntimeEnvironmentContext.WorkspaceBound::class, context::class)
        Unit
    }

    @Test
    fun `filesystem execution context requires exact worker mount`() = runBlocking {
        val service = WorkspaceApplicationService(
            projectRepository = TestProjectRepository(project),
            workspaceRepository = TestWorkspaceRepository(workspace),
        )

        assertFailsWith<IllegalStateException> {
            service.resolveExecution(WorkspaceMount.Id("missing-mount"))
        }
        Unit
    }

    @Test
    fun `management lifecycle keeps logical workspace separate from worker mount`() = runBlocking {
        val repository = TestWorkspaceRepository()
        val service = WorkspaceApplicationService(
            projectRepository = TestProjectRepository(project),
            workspaceRepository = repository,
        )

        val created = service.create(project.id, " Mac checkout ")
        assertEquals("Mac checkout", created.name)
        assertEquals(Workspace.Kind.FILESYSTEM, created.kind)
        assertNull(service.findMount(created.id, "mac-worker"))

        val mounted = service.attachFilesystem(created.id, " mac-worker ", " /repo ")
        assertEquals("mac-worker", mounted.mount.workerId)
        assertEquals("/repo", mounted.mount.rootPath)
        assertNotNull(service.findMount(mounted.mount.id))

        val renamed = service.update(created.id, "Primary checkout")
        assertEquals("Primary checkout", renamed.name)

        service.deleteMount(mounted.mount.id)
        assertNull(service.findMount(mounted.mount.id))
        service.delete(created.id)
        assertNull(service.findById(created.id))
    }
}

private class TestProjectRepository(
    project: Project,
) : ProjectRepository {
    private val projects = mutableMapOf(project.id to project)

    override suspend fun save(project: Project): Project = project.also { projects[it.id] = it }

    override suspend fun findById(id: Project.Id): Project? = projects[id]

    override suspend fun findAll(): List<Project> = projects.values.toList()

    override suspend fun findRecent(limit: Int): List<Project> = projects.values.take(limit)

    override suspend fun delete(id: Project.Id) {
        projects.remove(id)
    }

    override suspend fun exists(id: Project.Id): Boolean = id in projects
}

private class TestWorkspaceRepository(
    vararg workspaces: Workspace,
) : WorkspaceRepository {
    private val workspaces = workspaces.associateBy(Workspace::id).toMutableMap()
    private val mounts = mutableMapOf<Pair<Workspace.Id, String>, WorkspaceMount>()

    override suspend fun save(workspace: Workspace): Workspace =
        workspace.also { workspaces[it.id] = it }

    override suspend fun findById(id: Workspace.Id): Workspace? = workspaces[id]

    override suspend fun findByProject(projectId: Project.Id): List<Workspace> =
        workspaces.values.filter { it.projectId == projectId }

    override suspend fun delete(id: Workspace.Id) {
        workspaces.remove(id)
    }

    override suspend fun saveMount(mount: WorkspaceMount): WorkspaceMount =
        mount.also { mounts[it.workspaceId to it.workerId] = it }

    override suspend fun findMount(id: WorkspaceMount.Id): WorkspaceMount? =
        mounts.values.singleOrNull { it.id == id }

    override suspend fun findMount(workspaceId: Workspace.Id, workerId: String): WorkspaceMount? =
        mounts[workspaceId to workerId]

    override suspend fun findMountByPath(workerId: String, rootPath: String): WorkspaceMount? =
        mounts.values.singleOrNull { it.workerId == workerId && it.rootPath == rootPath }

    override suspend fun findMounts(workspaceId: Workspace.Id): List<WorkspaceMount> =
        mounts.values.filter { it.workspaceId == workspaceId }

    override suspend fun findMountsByWorker(workerId: String): List<WorkspaceMount> =
        mounts.values.filter { it.workerId == workerId }

    override suspend fun deleteMount(id: WorkspaceMount.Id) {
        mounts.entries.removeAll { it.value.id == id }
    }
}
