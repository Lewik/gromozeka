package com.gromozeka.application.service

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.RuntimeEnvironmentContext
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceExecutionContext
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.repository.ProjectRepository
import com.gromozeka.domain.repository.WorkspaceRepository
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.service.WorkspaceCatalogService
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path

@Service
class WorkspaceApplicationService(
    private val projectRepository: ProjectRepository,
    private val workspaceRepository: WorkspaceRepository,
) : WorkspaceDomainService, WorkspaceCatalogService {

    @Transactional
    override suspend fun createFilesystem(
        projectId: Project.Id,
        name: String,
        workerId: String,
        rootPath: String,
        id: Workspace.Id?,
    ): WorkspaceExecutionContext {
        val project = projectRepository.findById(projectId)
            ?: error("Project not found: ${projectId.value}")
        val workspaceName = name.trim()
        require(workspaceName.isNotEmpty()) { "Workspace name must not be blank" }
        val workspaceId = id ?: Workspace.Id(uuid7())
        require(workspaceRepository.findById(workspaceId) == null) {
            "Workspace already exists: ${workspaceId.value}"
        }
        val normalizedPath = normalizeExistingDirectory(rootPath)
        require(workspaceRepository.findMountByPath(workerId, normalizedPath) == null) {
            "Worker $workerId already mounts a workspace at $normalizedPath"
        }
        val now = Clock.System.now()
        val workspace = workspaceRepository.save(
            Workspace(
                id = workspaceId,
                projectId = projectId,
                name = workspaceName,
                kind = Workspace.Kind.FILESYSTEM,
                createdAt = now,
                updatedAt = now,
            )
        )
        val mount = workspaceRepository.saveMount(
            WorkspaceMount(
                workspaceId = workspace.id,
                workerId = normalizedWorkerId(workerId),
                rootPath = normalizedPath,
                createdAt = now,
                updatedAt = now,
            )
        )
        return WorkspaceExecutionContext(project, workspace, mount)
    }

    @Transactional
    override suspend fun attachFilesystem(
        workspaceId: Workspace.Id,
        workerId: String,
        rootPath: String,
    ): WorkspaceExecutionContext {
        val workspace = workspaceRepository.findById(workspaceId)
            ?: error("Workspace not found: ${workspaceId.value}")
        require(workspace.kind == Workspace.Kind.FILESYSTEM) {
            "Workspace ${workspaceId.value} is not a filesystem workspace"
        }
        val project = projectRepository.findById(workspace.projectId)
            ?: error("Project not found: ${workspace.projectId.value}")
        val normalizedWorkerId = normalizedWorkerId(workerId)
        val normalizedPath = normalizeExistingDirectory(rootPath)
        val pathOwner = workspaceRepository.findMountByPath(normalizedWorkerId, normalizedPath)
        require(pathOwner == null || pathOwner.workspaceId == workspaceId) {
            "Worker $normalizedWorkerId already mounts another workspace at $normalizedPath"
        }
        val existing = workspaceRepository.findMount(workspaceId, normalizedWorkerId)
        require(existing == null || existing.rootPath == normalizedPath) {
            "Workspace ${workspaceId.value} is already mounted by worker $normalizedWorkerId at ${existing?.rootPath}"
        }
        val now = Clock.System.now()
        val mount = existing ?: workspaceRepository.saveMount(
            WorkspaceMount(
                workspaceId = workspaceId,
                workerId = normalizedWorkerId,
                rootPath = normalizedPath,
                createdAt = now,
                updatedAt = now,
            )
        )
        return WorkspaceExecutionContext(project, workspace, mount)
    }

    override suspend fun findById(id: Workspace.Id): Workspace? =
        workspaceRepository.findById(id)

    override suspend fun findByProject(projectId: Project.Id): List<Workspace> =
        workspaceRepository.findByProject(projectId)

    override suspend fun findMount(workspaceId: Workspace.Id, workerId: String): WorkspaceMount? =
        workspaceRepository.findMount(workspaceId, normalizedWorkerId(workerId))

    override suspend fun findMounts(workspaceId: Workspace.Id): List<WorkspaceMount> =
        workspaceRepository.findMounts(workspaceId)

    override suspend fun findMountsByWorker(workerId: String): List<WorkspaceMount> =
        workspaceRepository.findMountsByWorker(normalizedWorkerId(workerId))

    override suspend fun findByWorkerPath(workerId: String, rootPath: String): WorkspaceExecutionContext? {
        val mount = workspaceRepository.findMountByPath(
            normalizedWorkerId(workerId),
            normalizeExistingDirectory(rootPath),
        ) ?: return null
        return resolveExecution(mount.workspaceId, mount.workerId)
    }

    override suspend fun resolveExecution(
        workspaceId: Workspace.Id,
        workerId: String,
    ): WorkspaceExecutionContext {
        val workspace = workspaceRepository.findById(workspaceId)
            ?: error("Workspace not found: ${workspaceId.value}")
        val project = projectRepository.findById(workspace.projectId)
            ?: error("Project not found: ${workspace.projectId.value}")
        val mount = workspaceRepository.findMount(workspaceId, normalizedWorkerId(workerId))
            ?: error("Workspace ${workspaceId.value} is not mounted by worker ${normalizedWorkerId(workerId)}")
        return WorkspaceExecutionContext(project, workspace, mount)
    }

    override suspend fun resolveRuntime(
        workspaceId: Workspace.Id,
        workerId: String,
    ): RuntimeEnvironmentContext.WorkspaceBound {
        val normalizedWorkerId = normalizedWorkerId(workerId)
        val workspace = workspaceRepository.findById(workspaceId)
            ?: error("Workspace not found: ${workspaceId.value}")
        val project = projectRepository.findById(workspace.projectId)
            ?: error("Project not found: ${workspace.projectId.value}")
        return RuntimeEnvironmentContext.WorkspaceBound(
            project = project,
            workspace = workspace,
            workerId = normalizedWorkerId,
            localMount = workspaceRepository.findMount(workspaceId, normalizedWorkerId),
        )
    }

    private fun normalizedWorkerId(workerId: String): String =
        workerId.trim().also { require(it.isNotEmpty()) { "Worker id must not be blank" } }

    private fun normalizeExistingDirectory(rootPath: String): String {
        require(rootPath.isNotBlank()) { "Workspace root path must not be blank" }
        val resolved = Path.of(rootPath).toRealPath()
        require(Files.isDirectory(resolved)) { "Workspace root path is not a directory: $resolved" }
        return resolved.toString()
    }
}
