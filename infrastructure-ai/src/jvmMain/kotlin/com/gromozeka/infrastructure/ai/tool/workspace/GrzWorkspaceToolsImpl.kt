package com.gromozeka.infrastructure.ai.tool.workspace

import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.service.WorkerWorkspaceStateService
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredProjectId
import com.gromozeka.domain.tool.workspace.AttachFilesystemWorkspaceRequest
import com.gromozeka.domain.tool.workspace.CreateFilesystemWorkspaceRequest
import com.gromozeka.domain.tool.workspace.GrzAttachFilesystemWorkspaceTool
import com.gromozeka.domain.tool.workspace.GrzCreateFilesystemWorkspaceTool
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzCreateFilesystemWorkspaceToolImpl(
    private val workspaceStateService: WorkerWorkspaceStateService,
) : GrzCreateFilesystemWorkspaceTool {
    override fun execute(
        request: CreateFilesystemWorkspaceRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any> {
        val projectId = context.requiredProjectId()
        val rootPath = normalizeExistingDirectory(request.root_path)
        val workspaceContext = runBlocking {
            workspaceStateService.createAndMountFilesystemWorkspace(
                projectId = projectId,
                name = request.name,
                rootPath = rootPath,
            )
        }
        return workspaceContext.toToolResult()
    }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzAttachFilesystemWorkspaceToolImpl(
    private val workspaceStateService: WorkerWorkspaceStateService,
) : GrzAttachFilesystemWorkspaceTool {
    override fun execute(
        request: AttachFilesystemWorkspaceRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any> {
        val projectId = context.requiredProjectId()
        val rootPath = normalizeExistingDirectory(request.root_path)
        val workspaceId = Workspace.Id(request.workspace_id)
        val workspaceContext = runBlocking {
            workspaceStateService.attachFilesystemWorkspace(
                projectId = projectId,
                workspaceId = workspaceId,
                rootPath = rootPath,
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
        "workspace_mount_id" to mount.id.value,
        "worker_id" to mount.workerId,
        "root_path" to mount.rootPath,
    )

private fun normalizeExistingDirectory(rootPath: String): String {
    require(rootPath.isNotBlank()) { "Workspace root path must not be blank" }
    val resolved = Path.of(rootPath).toRealPath()
    require(Files.isDirectory(resolved)) { "Workspace root path is not a directory: $resolved" }
    return resolved.toString()
}
