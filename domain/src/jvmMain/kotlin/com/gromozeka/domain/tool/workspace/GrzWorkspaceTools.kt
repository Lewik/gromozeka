package com.gromozeka.domain.tool.workspace

import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.WorkerManagementToolMetadata

data class CreateFilesystemWorkspaceRequest(
    val name: String,
    val root_path: String,
)

interface GrzCreateFilesystemWorkspaceTool : Tool<CreateFilesystemWorkspaceRequest, Map<String, Any>> {
    override val name: String
        get() = "grz_create_filesystem_workspace"

    override val metadata
        get() = WorkerManagementToolMetadata

    override val description: String
        get() = """
            Create a filesystem workspace in the current logical project and mount it on the selected worker.
            Use this only when the user wants to register a distinct checkout or filesystem tree.
            The directory must already exist on that worker. Different checkouts are different workspaces.
        """.trimIndent()

    override val requestType: Class<CreateFilesystemWorkspaceRequest>
        get() = CreateFilesystemWorkspaceRequest::class.java

    override fun execute(
        request: CreateFilesystemWorkspaceRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any>
}

data class AttachFilesystemWorkspaceRequest(
    val workspace_id: String,
    val root_path: String,
)

interface GrzAttachFilesystemWorkspaceTool : Tool<AttachFilesystemWorkspaceRequest, Map<String, Any>> {
    override val name: String
        get() = "grz_attach_filesystem_workspace"

    override val metadata
        get() = WorkerManagementToolMetadata

    override val description: String
        get() = """
            Mount an existing filesystem workspace from the current logical project on the selected worker.
            Use this only when the selected worker sees the same underlying filesystem tree represented by that workspace.
            A separate checkout must be created as a separate workspace instead.
        """.trimIndent()

    override val requestType: Class<AttachFilesystemWorkspaceRequest>
        get() = AttachFilesystemWorkspaceRequest::class.java

    override fun execute(
        request: AttachFilesystemWorkspaceRequest,
        context: ToolExecutionContext?,
    ): Map<String, Any>
}
