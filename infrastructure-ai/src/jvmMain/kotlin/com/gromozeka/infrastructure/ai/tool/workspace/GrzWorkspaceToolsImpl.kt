package com.gromozeka.infrastructure.ai.tool.workspace

import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredProjectId
import com.gromozeka.domain.tool.requiredWorkerId
import com.gromozeka.domain.tool.workspace.AttachFilesystemWorkspaceRequest
import com.gromozeka.domain.tool.workspace.CreateFilesystemWorkspaceRequest
import com.gromozeka.domain.tool.workspace.GrzAttachFilesystemWorkspaceTool
import com.gromozeka.domain.tool.workspace.GrzCreateFilesystemWorkspaceTool
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzCreateFilesystemWorkspaceToolImpl(
    private val workspaceService: WorkspaceDomainService,
) : GrzCreateFilesystemWorkspaceTool {
    override fun execute(
        request: CreateFilesystemWorkspaceRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any> {
        val projectId = context.requiredProjectId()
        val workerId = context.requiredWorkerId()
        val workspaceContext = runBlocking {
            workspaceService.createFilesystem(
                projectId = projectId,
                name = request.name,
                workerId = workerId.value,
                rootPath = request.root_path,
            )
        }
        return workspaceContext.toToolResult()
    }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzAttachFilesystemWorkspaceToolImpl(
    private val workspaceService: WorkspaceDomainService,
) : GrzAttachFilesystemWorkspaceTool {
    override fun execute(
        request: AttachFilesystemWorkspaceRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any> {
        val projectId = context.requiredProjectId()
        val workerId = context.requiredWorkerId()
        val workspaceId = Workspace.Id(request.workspace_id)
        val workspace = runBlocking {
            workspaceService.findById(workspaceId)
        } ?: error("Workspace not found: ${workspaceId.value}")
        require(workspace.projectId == projectId) {
            "Workspace ${workspace.id.value} belongs to project ${workspace.projectId.value}, not ${projectId.value}"
        }
        val workspaceContext = runBlocking {
            workspaceService.attachFilesystem(
                workspaceId = workspaceId,
                workerId = workerId.value,
                rootPath = request.root_path,
            )
        }
        return workspaceContext.toToolResult()
    }
}

private fun com.gromozeka.domain.model.WorkspaceExecutionContext.toToolResult(): Map<String, Any> =
    mapOf(
        "success" to true,
        "project_id" to project.id.value,
        "workspace_id" to workspace.id.value,
        "workspace_name" to workspace.name,
        "workspace_kind" to workspace.kind.name,
        "worker_id" to mount.workerId,
        "root_path" to mount.rootPath,
    )
