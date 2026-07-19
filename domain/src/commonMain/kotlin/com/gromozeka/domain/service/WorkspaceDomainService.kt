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
    suspend fun createFilesystem(
        projectId: Project.Id,
        name: String,
        workerId: String,
        rootPath: String,
        id: Workspace.Id? = null,
    ): WorkspaceExecutionContext

    suspend fun attachFilesystem(
        workspaceId: Workspace.Id,
        workerId: String,
        rootPath: String,
    ): WorkspaceExecutionContext

    suspend fun findById(id: Workspace.Id): Workspace?

    suspend fun findByProject(projectId: Project.Id): List<Workspace>

    suspend fun findMount(workspaceId: Workspace.Id, workerId: String): WorkspaceMount?

    suspend fun findMounts(workspaceId: Workspace.Id): List<WorkspaceMount>

    suspend fun findMountsByWorker(workerId: String): List<WorkspaceMount>

    suspend fun findByWorkerPath(workerId: String, rootPath: String): WorkspaceExecutionContext?

    suspend fun resolveExecution(workspaceId: Workspace.Id, workerId: String): WorkspaceExecutionContext

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
