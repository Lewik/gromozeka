package com.gromozeka.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Logical project associated with conversations and workspaces.
 *
 * A project groups conversations and related workspaces. It does not identify a
 * filesystem checkout or a worker. Physical filesystem access belongs to
 * [WorkspaceMount].
 *
 * Project persistence is an infrastructure detail. The domain layer works with
 * the unified Project entity without knowing whether fields are indexed,
 * denormalized, or stored as JSON by the repository.
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id unique project identifier (UUIDv7)
 * @property name human-readable project name
 * @property description optional project notes or purpose
 * @property createdAt timestamp when project was first created
 * @property lastUsedAt timestamp of last conversation in this project
 */
@Serializable
data class Project(
    val id: Id,
    val name: String,
    val description: String? = null,
    val createdAt: Instant,
    val lastUsedAt: Instant,
) {
    /**
     * Unique project identifier (UUIDv7).
     */
    @Serializable
    @JvmInline
    value class Id(val value: String)
}

/**
 * Logical workspace inside a project.
 *
 * Different physical checkouts of the same project are different workspaces.
 * Multiple workers may mount one workspace only when they see the same
 * underlying filesystem tree.
 */
@Serializable
data class Workspace(
    val id: Id,
    val projectId: Project.Id,
    val name: String,
    val kind: Kind,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(name.isNotBlank()) { "Workspace name must not be blank" }
    }

    @Serializable
    @JvmInline
    value class Id(val value: String) {
        init {
            require(value.isNotBlank()) { "Workspace id must not be blank" }
        }
    }

    @Serializable
    enum class Kind {
        FILESYSTEM,
    }
}

/**
 * Worker-specific physical mount of a logical workspace.
 */
@Serializable
data class WorkspaceMount(
    val workspaceId: Workspace.Id,
    val workerId: String,
    val rootPath: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(workerId.isNotBlank()) { "Workspace mount worker id must not be blank" }
        require(rootPath.isNotBlank()) { "Workspace mount root path must not be blank" }
    }
}

/**
 * Fully resolved filesystem execution context.
 */
@Serializable
data class WorkspaceExecutionContext(
    val project: Project,
    val workspace: Workspace,
    val mount: WorkspaceMount,
) {
    init {
        require(workspace.projectId == project.id) {
            "Workspace ${workspace.id.value} does not belong to project ${project.id.value}"
        }
        require(mount.workspaceId == workspace.id) {
            "Mount workspace ${mount.workspaceId.value} does not match workspace ${workspace.id.value}"
        }
    }
}

/**
 * Environment available to non-filesystem runtime work.
 *
 * Standalone operations such as global memory work have no project or
 * workspace. Conversation work is workspace-bound, but its current worker may
 * still have no local mount. Filesystem operations use
 * [WorkspaceExecutionContext] instead.
 */
sealed interface RuntimeEnvironmentContext {
    val workerId: String
    val workspaceRootPath: String?

    data class Standalone(
        override val workerId: String,
    ) : RuntimeEnvironmentContext {
        init {
            require(workerId.isNotBlank()) { "Runtime worker id must not be blank" }
        }

        override val workspaceRootPath: String? = null
    }

    data class WorkspaceBound(
        val project: Project,
        val workspace: Workspace,
        override val workerId: String,
        val localMount: WorkspaceMount?,
    ) : RuntimeEnvironmentContext {
        init {
            require(workerId.isNotBlank()) { "Runtime worker id must not be blank" }
            require(workspace.projectId == project.id) {
                "Workspace ${workspace.id.value} does not belong to project ${project.id.value}"
            }
            localMount?.let { mount ->
                require(mount.workspaceId == workspace.id) {
                    "Mount workspace ${mount.workspaceId.value} does not match workspace ${workspace.id.value}"
                }
                require(mount.workerId == workerId) {
                    "Mount worker ${mount.workerId} does not match runtime worker $workerId"
                }
            }
        }

        override val workspaceRootPath: String?
            get() = localMount?.rootPath
    }
}

fun WorkspaceExecutionContext.toRuntimeContext(): RuntimeEnvironmentContext.WorkspaceBound =
    RuntimeEnvironmentContext.WorkspaceBound(
        project = project,
        workspace = workspace,
        workerId = mount.workerId,
        localMount = mount,
    )
