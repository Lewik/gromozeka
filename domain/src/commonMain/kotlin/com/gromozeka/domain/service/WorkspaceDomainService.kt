package com.gromozeka.domain.service

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.RuntimeEnvironmentContext
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceExecutionContext
import com.gromozeka.domain.model.WorkspaceMount

/**
 * [SPECIFICATION] Lifecycle and resolution boundary for filesystem workspaces.
 */
interface WorkspaceDomainService {
    suspend fun createFilesystemWorkspace(
        projectId: Project.Id,
        name: String,
        id: Workspace.Id? = null,
    ): Workspace

    suspend fun createAndMountFilesystemWorkspace(
        projectId: Project.Id,
        name: String,
        workerId: String,
        rootPath: String,
        workspaceId: Workspace.Id? = null,
        mountId: WorkspaceMount.Id? = null,
    ): WorkspaceExecutionContext

    suspend fun attachFilesystem(
        workspaceId: Workspace.Id,
        workerId: String,
        rootPath: String,
        mountId: WorkspaceMount.Id? = null,
    ): WorkspaceExecutionContext

    suspend fun findById(id: Workspace.Id): Workspace?

    suspend fun findByProject(projectId: Project.Id): List<Workspace>

    suspend fun findMount(id: WorkspaceMount.Id): WorkspaceMount?

    suspend fun findMount(workspaceId: Workspace.Id, workerId: String): WorkspaceMount?

    suspend fun findMounts(workspaceId: Workspace.Id): List<WorkspaceMount>

    suspend fun findMountsByWorker(workerId: String): List<WorkspaceMount>

    suspend fun findByWorkerPath(workerId: String, rootPath: String): WorkspaceExecutionContext?

    suspend fun resolveExecution(mountId: WorkspaceMount.Id): WorkspaceExecutionContext

    suspend fun resolveRuntime(
        workspaceId: Workspace.Id,
        workerId: String,
    ): RuntimeEnvironmentContext.WorkspaceBound
}

/**
 * [SPECIFICATION] Read-only workspace topology available to remote clients.
 */
interface WorkspaceCatalogService {
    suspend fun findById(id: Workspace.Id): Workspace?

    suspend fun findByProject(projectId: Project.Id): List<Workspace>

    suspend fun findMounts(workspaceId: Workspace.Id): List<WorkspaceMount>
}

interface WorkspaceManagementService {
    suspend fun create(
        projectId: Project.Id,
        name: String,
    ): Workspace

    suspend fun update(
        workspaceId: Workspace.Id,
        name: String,
    ): Workspace

    suspend fun delete(workspaceId: Workspace.Id)

    suspend fun deleteMount(mountId: WorkspaceMount.Id)
}
