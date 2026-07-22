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
import com.gromozeka.domain.service.WorkspaceManagementService
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WorkspaceApplicationService(
    private val projectRepository: ProjectRepository,
    private val workspaceRepository: WorkspaceRepository,
) : WorkspaceDomainService, WorkspaceCatalogService, WorkspaceManagementService {

    override suspend fun create(projectId: Project.Id, name: String): Workspace =
        createFilesystemWorkspace(projectId, name)

    @Transactional
    override suspend fun createFilesystemWorkspace(
        projectId: Project.Id,
        name: String,
        id: Workspace.Id?,
    ): Workspace {
        require(projectRepository.findById(projectId) != null) {
            "Project not found: ${projectId.value}"
        }
        val workspaceName = name.trim()
        require(workspaceName.isNotEmpty()) { "Workspace name must not be blank" }
        val workspaceId = id ?: Workspace.Id(uuid7())
        require(workspaceRepository.findById(workspaceId) == null) {
            "Workspace already exists: ${workspaceId.value}"
        }
        val now = Clock.System.now()
        return workspaceRepository.save(
            Workspace(
                id = workspaceId,
                projectId = projectId,
                name = workspaceName,
                kind = Workspace.Kind.FILESYSTEM,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    @Transactional
    override suspend fun createAndMountFilesystemWorkspace(
        projectId: Project.Id,
        name: String,
        workerId: String,
        rootPath: String,
        workspaceId: Workspace.Id?,
        mountId: WorkspaceMount.Id?,
    ): WorkspaceExecutionContext {
        val workspace = createFilesystemWorkspace(projectId, name, workspaceId)
        return attachFilesystem(workspace.id, workerId, rootPath, mountId)
    }

    @Transactional
    override suspend fun attachFilesystem(
        workspaceId: Workspace.Id,
        workerId: String,
        rootPath: String,
        mountId: WorkspaceMount.Id?,
    ): WorkspaceExecutionContext {
        val workspace = workspaceRepository.findById(workspaceId)
            ?: error("Workspace not found: ${workspaceId.value}")
        require(workspace.kind == Workspace.Kind.FILESYSTEM) {
            "Workspace ${workspaceId.value} is not a filesystem workspace"
        }
        val project = projectRepository.findById(workspace.projectId)
            ?: error("Project not found: ${workspace.projectId.value}")
        val normalizedWorkerId = normalizedWorkerId(workerId)
        val normalizedPath = normalizedRootPath(rootPath)
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
                id = mountId ?: WorkspaceMount.Id(uuid7()),
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

    override suspend fun findMount(id: WorkspaceMount.Id): WorkspaceMount? =
        workspaceRepository.findMount(id)

    override suspend fun findMount(workspaceId: Workspace.Id, workerId: String): WorkspaceMount? =
        workspaceRepository.findMount(workspaceId, normalizedWorkerId(workerId))

    override suspend fun findMounts(workspaceId: Workspace.Id): List<WorkspaceMount> =
        workspaceRepository.findMounts(workspaceId)

    @Transactional
    override suspend fun update(workspaceId: Workspace.Id, name: String): Workspace {
        val workspace = workspaceRepository.findById(workspaceId)
            ?: error("Workspace not found: ${workspaceId.value}")
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Workspace name must not be blank" }
        return workspaceRepository.save(
            workspace.copy(name = normalizedName, updatedAt = Clock.System.now())
        )
    }

    @Transactional
    override suspend fun delete(workspaceId: Workspace.Id) {
        require(workspaceRepository.findById(workspaceId) != null) {
            "Workspace not found: ${workspaceId.value}"
        }
        workspaceRepository.delete(workspaceId)
    }

    @Transactional
    override suspend fun deleteMount(mountId: WorkspaceMount.Id) {
        require(workspaceRepository.findMount(mountId) != null) {
            "Workspace mount not found: ${mountId.value}"
        }
        workspaceRepository.deleteMount(mountId)
    }

    override suspend fun findMountsByWorker(workerId: String): List<WorkspaceMount> =
        workspaceRepository.findMountsByWorker(normalizedWorkerId(workerId))

    override suspend fun findByWorkerPath(workerId: String, rootPath: String): WorkspaceExecutionContext? {
        val mount = workspaceRepository.findMountByPath(
            normalizedWorkerId(workerId),
            normalizedRootPath(rootPath),
        ) ?: return null
        return resolveExecution(mount.id)
    }

    override suspend fun resolveExecution(
        mountId: WorkspaceMount.Id,
    ): WorkspaceExecutionContext {
        val mount = workspaceRepository.findMount(mountId)
            ?: error("Workspace mount not found: ${mountId.value}")
        val workspaceId = mount.workspaceId
        val workspace = workspaceRepository.findById(workspaceId)
            ?: error("Workspace not found: ${workspaceId.value}")
        val project = projectRepository.findById(workspace.projectId)
            ?: error("Project not found: ${workspace.projectId.value}")
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

    private fun normalizedRootPath(rootPath: String): String =
        rootPath.trim().also { require(it.isNotEmpty()) { "Workspace root path must not be blank" } }
}
