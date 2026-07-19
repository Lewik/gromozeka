package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceContextReference(
    val relativePath: String,
    val name: String,
    val kind: Kind,
) {
    init {
        require(relativePath.isNotBlank()) { "Workspace context path cannot be blank" }
        require(!relativePath.startsWith('/')) { "Workspace context path must be relative: $relativePath" }
        require(!WINDOWS_ABSOLUTE_PATH.matches(relativePath)) {
            "Workspace context path must be relative: $relativePath"
        }
        require(relativePath.split('/').none { it == ".." }) {
            "Workspace context path must stay inside the workspace: $relativePath"
        }
        require(name.isNotBlank()) { "Workspace context name cannot be blank" }
    }

    @Serializable
    enum class Kind {
        DIRECTORY,
        FILE,
    }

    private companion object {
        val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")
    }
}
