package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class WorkspacePath(
    val path: String,
    val name: String,
    val kind: Kind,
) {
    init {
        require(path.isNotBlank()) { "Workspace path cannot be blank" }
        require(name.isNotBlank()) { "Workspace path name cannot be blank" }
    }

    @Serializable
    enum class Kind {
        DIRECTORY,
        FILE,
    }
}

@Serializable
data class WorkspaceDirectoryListing(
    val directory: WorkspacePath,
    val parentPath: String?,
    val entries: List<WorkspacePath>,
) {
    init {
        require(directory.kind == WorkspacePath.Kind.DIRECTORY) {
            "Workspace listing root must be a directory"
        }
    }
}

@Serializable
data class WorkspaceContextReference(
    val path: String,
    val name: String,
    val kind: WorkspacePath.Kind,
) {
    init {
        require(path.isNotBlank()) { "Workspace context path cannot be blank" }
        require(name.isNotBlank()) { "Workspace context name cannot be blank" }
    }
}
