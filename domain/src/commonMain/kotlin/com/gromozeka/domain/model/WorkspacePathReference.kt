package com.gromozeka.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WorkspacePathReference(
    @SerialName("workspace_mount_id")
    val workspaceMountId: WorkspaceMount.Id,
    val path: String,
) {
    init {
        require(path.isNotBlank()) { "Workspace path must not be blank" }
    }
}
