package com.gromozeka.server

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.WorkerPermission
import com.gromozeka.domain.model.WorkerProjectGrant
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.model.WorkerUserGrant
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.service.WorkspaceManagementService
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service

@Service
internal class ControlMcpProjectWorkspaceTools(
    private val projectAccessService: ProjectAccessService,
    private val workspaceService: WorkspaceDomainService,
    private val workspaceManagementService: WorkspaceManagementService,
    private val workerRegistry: ConversationRuntimeWorkerRegistry,
    private val workerAccessService: WorkerAccessService,
) : ControlMcpToolProvider {
    override val tools: List<ControlMcpTool> = listOf(
        controlMcpTool(
            name = "grz_project_list",
            description = "List every logical Gromozeka project.",
            readOnly = true,
        ) {
            buildJsonObject {
                put(
                    "projects",
                    controlMcpJson.encodeToJsonElement(
                        ListSerializer(Project.serializer()),
                        projectAccessService.findAll(user.id),
                    )
                )
            }
        },
        controlMcpTool(
            name = "grz_project_get",
            description = "Read one logical Gromozeka project by id.",
            inputSchema = idSchema("projectId", "Project id."),
            readOnly = true,
        ) { input ->
            val id = input.requiredString("projectId")
            val project = projectAccessService.findById(user.id, Project.Id(id))
                ?: notFound("Project", id)
            entityResult("project", Project.serializer(), project)
        },
        controlMcpTool(
            name = "grz_project_create",
            description = "Create a logical project. A project does not imply a filesystem workspace.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "name" to ControlMcpSchemas.string("Human-readable project name."),
                    "description" to ControlMcpSchemas.string("Optional project description."),
                ),
                required = listOf("name"),
            ),
            readOnly = false,
        ) { input ->
            entityResult(
                "project",
                Project.serializer(),
                projectAccessService.create(
                    actorUserId = user.id,
                    name = input.requiredString("name"),
                    description = input.optionalString("description"),
                )
            )
        },
        controlMcpTool(
            name = "grz_project_update",
            description = "Replace a project's mutable name and description.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "projectId" to ControlMcpSchemas.string("Project id."),
                    "name" to ControlMcpSchemas.string("Human-readable project name."),
                    "description" to ControlMcpSchemas.string("Optional project description."),
                ),
                required = listOf("projectId", "name"),
            ),
            readOnly = false,
            idempotent = true,
        ) { input ->
            entityResult(
                "project",
                Project.serializer(),
                projectAccessService.update(
                    actorUserId = user.id,
                    id = Project.Id(input.requiredString("projectId")),
                    name = input.requiredString("name"),
                    description = input.optionalString("description"),
                )
            )
        },
        controlMcpTool(
            name = "grz_project_delete",
            description = "Delete a project and its project-owned data. This is destructive.",
            inputSchema = idSchema("projectId", "Project id."),
            readOnly = false,
            destructive = true,
        ) { input ->
            val id = input.requiredString("projectId")
            projectAccessService.delete(user.id, Project.Id(id))
            deletedResult("project", id)
        },
        controlMcpTool(
            name = "grz_workspace_list",
            description = "List filesystem workspaces in one project, including their worker-specific mounts.",
            inputSchema = idSchema("projectId", "Project id."),
            readOnly = true,
        ) { input ->
            val projectId = Project.Id(input.requiredString("projectId"))
            projectAccessService.requirePermission(user.id, projectId, ProjectPermission.READ)
            val workspaces = workspaceService.findByProject(projectId)
            buildJsonObject {
                put(
                    "workspaces",
                    kotlinx.serialization.json.JsonArray(
                        workspaces.map { workspaceWithMounts(it) }
                    )
                )
            }
        },
        controlMcpTool(
            name = "grz_workspace_get",
            description = "Read one filesystem workspace and every worker mount attached to it.",
            inputSchema = idSchema("workspaceId", "Workspace id."),
            readOnly = true,
        ) { input ->
            val id = input.requiredString("workspaceId")
            val workspace = requireWorkspace(Workspace.Id(id), ProjectPermission.READ)
            workspaceWithMounts(workspace)
        },
        controlMcpTool(
            name = "grz_workspace_create",
            description = "Create an unmounted filesystem workspace inside a project.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "projectId" to ControlMcpSchemas.string("Owning project id."),
                    "name" to ControlMcpSchemas.string("Workspace name."),
                ),
                required = listOf("projectId", "name"),
            ),
            readOnly = false,
        ) { input ->
            val projectId = Project.Id(input.requiredString("projectId"))
            projectAccessService.requirePermission(user.id, projectId, ProjectPermission.WRITE)
            entityResult(
                "workspace",
                Workspace.serializer(),
                workspaceService.createFilesystemWorkspace(
                    projectId = projectId,
                    name = input.requiredString("name"),
                )
            )
        },
        controlMcpTool(
            name = "grz_workspace_update",
            description = "Rename a filesystem workspace.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "workspaceId" to ControlMcpSchemas.string("Workspace id."),
                    "name" to ControlMcpSchemas.string("New workspace name."),
                ),
                required = listOf("workspaceId", "name"),
            ),
            readOnly = false,
        ) { input ->
            val workspaceId = Workspace.Id(input.requiredString("workspaceId"))
            requireWorkspace(workspaceId, ProjectPermission.WRITE)
            entityResult(
                "workspace",
                Workspace.serializer(),
                workspaceManagementService.update(
                    workspaceId = workspaceId,
                    name = input.requiredString("name"),
                )
            )
        },
        controlMcpTool(
            name = "grz_workspace_delete",
            description = "Delete a logical workspace and its mounts. This does not delete files from workers.",
            inputSchema = idSchema("workspaceId", "Workspace id."),
            readOnly = false,
            destructive = true,
        ) { input ->
            val id = input.requiredString("workspaceId")
            val workspaceId = Workspace.Id(id)
            requireWorkspace(workspaceId, ProjectPermission.ADMIN)
            workspaceManagementService.delete(workspaceId)
            deletedResult("workspace", id)
        },
        controlMcpTool(
            name = "grz_workspace_mount_create",
            description = "Attach a filesystem workspace to one exact worker-local root path.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "workspaceId" to ControlMcpSchemas.string("Workspace id."),
                    "workerId" to ControlMcpSchemas.string("Exact worker id."),
                    "rootPath" to ControlMcpSchemas.string("Absolute worker-local workspace root path."),
                ),
                required = listOf("workspaceId", "workerId", "rootPath"),
            ),
            readOnly = false,
        ) { input ->
            val workspaceId = Workspace.Id(input.requiredString("workspaceId"))
            val workspace = requireWorkspace(workspaceId, ProjectPermission.WRITE)
            val workerId = ConversationRuntimeWorkerId(input.requiredString("workerId"))
            workerAccessService.requirePermission(
                actor = user,
                workerId = workerId,
                permission = WorkerPermission.USE,
                projectId = workspace.projectId,
            )
            val execution = workspaceService.attachFilesystem(
                workspaceId = workspaceId,
                workerId = workerId.value,
                rootPath = input.requiredString("rootPath"),
            )
            buildJsonObject {
                put("project", controlMcpJson.encodeToJsonElement(Project.serializer(), execution.project))
                put("workspace", controlMcpJson.encodeToJsonElement(Workspace.serializer(), execution.workspace))
                put("mount", controlMcpJson.encodeToJsonElement(WorkspaceMount.serializer(), execution.mount))
            }
        },
        controlMcpTool(
            name = "grz_workspace_mount_delete",
            description = "Detach a workspace mount. This does not delete the workspace or worker files.",
            inputSchema = idSchema("mountId", "Workspace mount id."),
            readOnly = false,
            destructive = true,
        ) { input ->
            val id = input.requiredString("mountId")
            val mountId = WorkspaceMount.Id(id)
            val mount = workspaceService.findMount(mountId) ?: notFound("Workspace mount", id)
            requireWorkspace(mount.workspaceId, ProjectPermission.WRITE)
            workspaceManagementService.deleteMount(mountId)
            deletedResult("workspace_mount", id)
        },
        controlMcpTool(
            name = "grz_worker_list",
            description = "List registered worker sessions, capabilities, advertised tools, and heartbeat state.",
            readOnly = true,
        ) {
            val accessibleWorkerIds = workerAccessService.listAccessible(user)
                .mapTo(mutableSetOf()) { it.id }
            buildJsonObject {
                put(
                    "workers",
                    controlMcpJson.encodeToJsonElement(
                        ListSerializer(com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration.serializer()),
                        workerRegistry.list().filter { it.identity.workerId in accessibleWorkerIds },
                    )
                )
            }
        },
        controlMcpTool(
            name = "grz_worker_access_get",
            description = "Read one worker's owner, runtime-wide access, and explicit user and project grants.",
            inputSchema = idSchema("workerId", "Worker id."),
            readOnly = true,
        ) { input ->
            val workerId = ConversationRuntimeWorkerId(input.requiredString("workerId"))
            val worker = workerAccessService.requirePermission(
                actor = user,
                workerId = workerId,
                permission = WorkerPermission.MANAGE,
            )
            buildJsonObject {
                put("worker", controlMcpJson.encodeToJsonElement(WorkerResource.serializer(), worker))
                put(
                    "userGrants",
                    controlMcpJson.encodeToJsonElement(
                        ListSerializer(WorkerUserGrant.serializer()),
                        workerAccessService.listUserGrants(user, workerId),
                    )
                )
                put(
                    "projectGrants",
                    controlMcpJson.encodeToJsonElement(
                        ListSerializer(WorkerProjectGrant.serializer()),
                        workerAccessService.listProjectGrants(user, workerId),
                    )
                )
            }
        },
        controlMcpTool(
            name = "grz_worker_user_grant",
            description = "Allow one active user to use a worker.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "workerId" to ControlMcpSchemas.string("Worker id."),
                    "userId" to ControlMcpSchemas.string("User id."),
                ),
                required = listOf("workerId", "userId"),
            ),
            readOnly = false,
            idempotent = true,
        ) { input ->
            entityResult(
                "grant",
                WorkerUserGrant.serializer(),
                workerAccessService.grantUser(
                    actor = user,
                    workerId = ConversationRuntimeWorkerId(input.requiredString("workerId")),
                    userId = com.gromozeka.domain.model.User.Id(input.requiredString("userId")),
                )
            )
        },
        controlMcpTool(
            name = "grz_worker_user_revoke",
            description = "Remove one user's direct access to a worker.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "workerId" to ControlMcpSchemas.string("Worker id."),
                    "userId" to ControlMcpSchemas.string("User id."),
                ),
                required = listOf("workerId", "userId"),
            ),
            readOnly = false,
            destructive = true,
            idempotent = true,
        ) { input ->
            val workerId = input.requiredString("workerId")
            val userId = input.requiredString("userId")
            workerAccessService.revokeUser(
                actor = user,
                workerId = ConversationRuntimeWorkerId(workerId),
                userId = com.gromozeka.domain.model.User.Id(userId),
            )
            deletedResult("worker_user_grant", "$workerId:$userId")
        },
        controlMcpTool(
            name = "grz_worker_project_grant",
            description = "Allow writable members of one project to use a worker.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "workerId" to ControlMcpSchemas.string("Worker id."),
                    "projectId" to ControlMcpSchemas.string("Project id."),
                ),
                required = listOf("workerId", "projectId"),
            ),
            readOnly = false,
            idempotent = true,
        ) { input ->
            entityResult(
                "grant",
                WorkerProjectGrant.serializer(),
                workerAccessService.grantProject(
                    actor = user,
                    workerId = ConversationRuntimeWorkerId(input.requiredString("workerId")),
                    projectId = Project.Id(input.requiredString("projectId")),
                )
            )
        },
        controlMcpTool(
            name = "grz_worker_project_revoke",
            description = "Remove one project's access to a worker.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "workerId" to ControlMcpSchemas.string("Worker id."),
                    "projectId" to ControlMcpSchemas.string("Project id."),
                ),
                required = listOf("workerId", "projectId"),
            ),
            readOnly = false,
            destructive = true,
            idempotent = true,
        ) { input ->
            val workerId = input.requiredString("workerId")
            val projectId = input.requiredString("projectId")
            workerAccessService.revokeProject(
                actor = user,
                workerId = ConversationRuntimeWorkerId(workerId),
                projectId = Project.Id(projectId),
            )
            deletedResult("worker_project_grant", "$workerId:$projectId")
        },
        controlMcpTool(
            name = "grz_worker_runtime_access_set",
            description = "Enable or disable worker use for every authenticated user.",
            inputSchema = ControlMcpSchemas.objectSchema(
                properties = mapOf(
                    "workerId" to ControlMcpSchemas.string("Worker id."),
                    "enabled" to ControlMcpSchemas.boolean("Whether runtime-wide use is enabled."),
                ),
                required = listOf("workerId", "enabled"),
            ),
            readOnly = false,
            idempotent = true,
        ) { input ->
            entityResult(
                "worker",
                WorkerResource.serializer(),
                workerAccessService.setRuntimeWideAccess(
                    actor = user,
                    workerId = ConversationRuntimeWorkerId(input.requiredString("workerId")),
                    enabled = input.optionalBoolean("enabled", false),
                )
            )
        },
        controlMcpTool(
            name = "grz_worker_revoke",
            description = "Revoke a worker and all future use. This is destructive.",
            inputSchema = idSchema("workerId", "Worker id."),
            readOnly = false,
            destructive = true,
            idempotent = true,
        ) { input ->
            entityResult(
                "worker",
                WorkerResource.serializer(),
                workerAccessService.revokeWorker(
                    actor = user,
                    workerId = ConversationRuntimeWorkerId(input.requiredString("workerId")),
                )
            )
        },
    )

    private suspend fun workspaceWithMounts(workspace: Workspace): JsonObject =
        buildJsonObject {
            put("workspace", controlMcpJson.encodeToJsonElement(Workspace.serializer(), workspace))
            put(
                "mounts",
                controlMcpJson.encodeToJsonElement(
                    ListSerializer(WorkspaceMount.serializer()),
                    workspaceService.findMounts(workspace.id),
                )
            )
        }

    private suspend fun ControlMcpCallContext.requireWorkspace(
        workspaceId: Workspace.Id,
        permission: ProjectPermission,
    ): Workspace {
        val workspace = workspaceService.findById(workspaceId)
            ?: notFound("Workspace", workspaceId.value)
        projectAccessService.requirePermission(user.id, workspace.projectId, permission)
        return workspace
    }
}

internal fun idSchema(field: String, description: String) =
    ControlMcpSchemas.objectSchema(
        properties = mapOf(field to ControlMcpSchemas.string(description)),
        required = listOf(field),
    )

internal fun <T> entityResult(
    name: String,
    serializer: kotlinx.serialization.KSerializer<T>,
    value: T,
): JsonObject = buildJsonObject {
    put(name, controlMcpJson.encodeToJsonElement(serializer, value))
}

internal fun deletedResult(entity: String, id: String): JsonObject =
    buildJsonObject {
        put("deleted", true)
        put("entity", entity)
        put("id", id)
    }
