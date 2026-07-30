package com.gromozeka.domain.service

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceExecutionContext
import com.gromozeka.domain.model.WorkspaceMount

interface WorkerWorkspaceStateService {
    suspend fun createAndMountFilesystemWorkspace(
        projectId: Project.Id,
        name: String,
        rootPath: String,
    ): WorkspaceExecutionContext

    suspend fun attachFilesystemWorkspace(
        projectId: Project.Id,
        workspaceId: Workspace.Id,
        rootPath: String,
    ): WorkspaceExecutionContext

    suspend fun findProjectMounts(projectId: Project.Id): List<WorkspaceMount>
}
