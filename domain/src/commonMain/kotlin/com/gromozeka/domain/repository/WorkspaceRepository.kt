package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount

/**
 * [SPECIFICATION] Persistence boundary for logical workspaces and worker mounts.
 */
interface WorkspaceRepository {
    suspend fun save(workspace: Workspace): Workspace

    suspend fun findById(id: Workspace.Id): Workspace?

    suspend fun findByProject(projectId: Project.Id): List<Workspace>

    suspend fun delete(id: Workspace.Id)

    suspend fun saveMount(mount: WorkspaceMount): WorkspaceMount

    suspend fun findMount(workspaceId: Workspace.Id, workerId: String): WorkspaceMount?

    suspend fun findMountByPath(workerId: String, rootPath: String): WorkspaceMount?

    suspend fun findMounts(workspaceId: Workspace.Id): List<WorkspaceMount>

    suspend fun findMountsByWorker(workerId: String): List<WorkspaceMount>

    suspend fun deleteMount(workspaceId: Workspace.Id, workerId: String)
}
