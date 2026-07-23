package com.gromozeka.application.service

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.shared.utils.sha256
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.springframework.stereotype.Service

const val AI_TOOL_EXECUTION_TARGET_FIELD = "execution_target"
const val AI_TOOL_EXECUTION_WORKER_ID_FIELD = "worker_id"
const val AI_TOOL_EXECUTION_WORKSPACE_MOUNT_ID_FIELD = "workspace_mount_id"

data class DistributedAiTool(
    val descriptor: AiToolDescriptor,
    val workers: List<DistributedAiToolWorker>,
)

data class DistributedAiToolWorker(
    val workerId: ConversationRuntimeWorkerId,
    val workspaceMounts: List<WorkspaceMount>,
)

data class DistributedAiToolCatalogSnapshot(
    val tools: List<AiToolCallback>,
    val entries: Map<String, DistributedAiTool>,
    val registrations: List<ConversationRuntimeWorkerRegistration>,
    val environmentRevision: String,
    val environmentPrompt: String,
)

@Service
class DistributedAiToolCatalog(
    private val workerRegistry: ConversationRuntimeWorkerRegistry,
    private val workspaceService: WorkspaceDomainService,
) {
    private val json = Json

    suspend fun snapshot(
        project: Project,
    ): DistributedAiToolCatalogSnapshot {
        val now = Clock.System.now()
        val staleBefore = now - ConversationRuntimeTiming.workerRegistrationStaleAfter
        val knownRegistrations = workerRegistry.list()
            .sortedBy { it.identity.workerId.value }
        val onlineRegistrations = knownRegistrations.filter { it.isOnline(staleBefore) }
        val projectWorkspaces = workspaceService.findByProject(project.id)
            .associateBy { it.id }
        val projectMounts = projectWorkspaces.keys
            .flatMap { workspaceService.findMounts(it) }
            .sortedBy { it.id.value }
        val mountsByOnlineWorker = onlineRegistrations.associate { registration ->
            registration.identity.workerId to projectMounts
                .filter { it.workerId == registration.identity.workerId.value }
        }
        val entries = onlineRegistrations
            .flatMap { registration ->
                registration.tools.map { descriptor -> descriptor.definition.name to (registration to descriptor) }
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .mapNotNull { (toolName, advertised) ->
                val descriptors = advertised.map { it.second }.distinct()
                check(descriptors.size == 1) {
                    "Online workers advertise conflicting definitions for tool '$toolName': " +
                        advertised.joinToString { it.first.identity.workerId.value }
                }
                val descriptor = descriptors.single()
                val workers = advertised
                    .map { (registration, _) ->
                        DistributedAiToolWorker(
                            workerId = registration.identity.workerId,
                            workspaceMounts = mountsByOnlineWorker.getValue(registration.identity.workerId),
                        )
                    }
                    .filter {
                        descriptor.metadata.executionScope == AiToolExecutionScope.CONVERSATION_RUNTIME ||
                            descriptor.metadata.executionScope == AiToolExecutionScope.WORKER ||
                            descriptor.metadata.executionScope == AiToolExecutionScope.COMMAND_TASK_OWNER ||
                            it.workspaceMounts.isNotEmpty()
                    }
                    .sortedBy { it.workerId.value }
                workers.takeIf { it.isNotEmpty() }
                    ?.let { toolName to DistributedAiTool(descriptor, it) }
            }
            .toMap()
            .toSortedMap()
        val callbacks = entries.values.map(::modelCallback)
        val environmentTopology = buildEnvironmentTopology(
            project = project,
            knownRegistrations = knownRegistrations,
            staleBefore = staleBefore,
            projectWorkspaces = projectWorkspaces,
            projectMounts = projectMounts,
        )
        val environmentRevision = environmentTopology.toString().sha256()

        return DistributedAiToolCatalogSnapshot(
            tools = callbacks,
            entries = entries,
            registrations = onlineRegistrations,
            environmentRevision = environmentRevision,
            environmentPrompt = buildEnvironmentPrompt(
                revision = environmentRevision,
                topology = environmentTopology,
            ),
        )
    }

    private fun modelCallback(tool: DistributedAiTool): AiToolCallback =
        object : AiToolCallback {
            override val definition: AiToolDefinition = tool.descriptor.definition.copy(
                description = tool.descriptor.definition.description.withExecutionTargetDescription(
                    tool.descriptor.metadata.executionScope
                ),
                inputSchema = tool.descriptor.definition.inputSchema.withExecutionTargetSchema(tool),
            )
            override val metadata: AiToolMetadata = tool.descriptor.metadata

            override fun call(toolInput: String, context: ToolExecutionContext?): String =
                error("Distributed tool descriptors cannot execute locally")
        }

    private fun String.withExecutionTargetDescription(scope: AiToolExecutionScope): String {
        val targetDescription = when (scope) {
            AiToolExecutionScope.CONVERSATION_RUNTIME -> return this
            AiToolExecutionScope.WORKER ->
                "Select the exact online worker in `$AI_TOOL_EXECUTION_TARGET_FIELD`."
            AiToolExecutionScope.WORKSPACE ->
                "Select the exact online filesystem mount in `$AI_TOOL_EXECUTION_TARGET_FIELD`."
            AiToolExecutionScope.COMMAND_TASK_OWNER ->
                "The command task ID routes this call to the worker and mount that own the task."
        }
        return "$this\n\n$targetDescription"
    }

    private fun String.withExecutionTargetSchema(tool: DistributedAiTool): String {
        val schema = json.parseToJsonElement(this).jsonObject
        val properties = schema["properties"]?.jsonObject.orEmpty()
        check(AI_TOOL_EXECUTION_TARGET_FIELD !in properties) {
            "Tool '${tool.descriptor.definition.name}' already declares reserved field " +
                "'$AI_TOOL_EXECUTION_TARGET_FIELD'"
        }
        if (
            tool.descriptor.metadata.executionScope == AiToolExecutionScope.CONVERSATION_RUNTIME ||
            tool.descriptor.metadata.executionScope == AiToolExecutionScope.COMMAND_TASK_OWNER
        ) {
            return schema.toString()
        }
        val required = schema["required"]?.jsonArray.orEmpty()
            .map { it.jsonPrimitive.content }
            .toMutableSet()
            .apply { add(AI_TOOL_EXECUTION_TARGET_FIELD) }
        val workspaceRequired = tool.descriptor.metadata.executionScope == AiToolExecutionScope.WORKSPACE

        val targetSchema = buildJsonObject {
            put("type", "object")
            put(
                "description",
                if (workspaceRequired) {
                    "Exact filesystem workspace mount for this call."
                } else {
                    "Exact worker for this call."
                }
            )
            putJsonObject("properties") {
                if (workspaceRequired) {
                    putJsonObject(AI_TOOL_EXECUTION_WORKSPACE_MOUNT_ID_FIELD) {
                        put("type", "string")
                        put(
                            "description",
                            "Exact workspace mount ID from the current execution environment."
                        )
                    }
                } else {
                    putJsonObject(AI_TOOL_EXECUTION_WORKER_ID_FIELD) {
                        put("type", "string")
                        put("description", "Exact worker ID from the current execution environment.")
                    }
                }
            }
            putJsonArray("required") {
                if (workspaceRequired) {
                    add(JsonPrimitive(AI_TOOL_EXECUTION_WORKSPACE_MOUNT_ID_FIELD))
                } else {
                    add(JsonPrimitive(AI_TOOL_EXECUTION_WORKER_ID_FIELD))
                }
            }
            put("additionalProperties", false)
        }
        val rewritten = JsonObject(
            schema + mapOf(
                "properties" to JsonObject(properties + (AI_TOOL_EXECUTION_TARGET_FIELD to targetSchema)),
                "required" to JsonArray(required.sorted().map(::JsonPrimitive)),
                "additionalProperties" to JsonPrimitive(false),
            )
        )
        return rewritten.toString()
    }

    private fun buildEnvironmentTopology(
        project: Project,
        knownRegistrations: List<ConversationRuntimeWorkerRegistration>,
        staleBefore: kotlinx.datetime.Instant,
        projectWorkspaces: Map<Workspace.Id, Workspace>,
        projectMounts: List<WorkspaceMount>,
    ): JsonObject {
        val registrationsByWorker = knownRegistrations.associateBy { it.identity.workerId.value }
        val workerIds = (registrationsByWorker.keys + projectMounts.map { it.workerId }).sorted()
        return buildJsonObject {
            putJsonObject("project") {
                put("id", project.id.value)
                put("name", project.name)
                project.description?.let { put("description", it) }
            }
            putJsonArray("workers") {
                workerIds.forEach { workerId ->
                    val registration = registrationsByWorker[workerId]
                    add(buildJsonObject {
                        put("worker_id", workerId)
                        put("status", if (registration?.isOnline(staleBefore) == true) "online" else "offline")
                        registration?.let {
                            put("version", it.version)
                            putJsonArray("capabilities") {
                                it.capabilities.map { capability -> capability.name }
                                    .sorted()
                                    .forEach { capability -> add(JsonPrimitive(capability)) }
                            }
                        }
                    })
                }
            }
            putJsonArray("workspaces") {
                projectWorkspaces.values.sortedBy { it.id.value }.forEach { workspace ->
                    add(buildJsonObject {
                        put("workspace_id", workspace.id.value)
                        put("name", workspace.name)
                        put("kind", workspace.kind.name.lowercase())
                        putJsonArray("mounts") {
                            projectMounts.filter { it.workspaceId == workspace.id }.forEach { mount ->
                                add(buildJsonObject {
                                    put("workspace_mount_id", mount.id.value)
                                    put("worker_id", mount.workerId)
                                    put(
                                        "status",
                                        if (registrationsByWorker[mount.workerId]?.isOnline(staleBefore) == true) {
                                            "online"
                                        } else {
                                            "offline"
                                        },
                                    )
                                    put("root_path", mount.rootPath)
                                })
                            }
                        }
                    })
                }
            }
        }
    }

    private fun buildEnvironmentPrompt(
        revision: String,
        topology: JsonObject,
    ): String =
        buildString {
            append("<execution_environment revision=\"")
            append(revision)
            append("\">\n")
            append("Server is the canonical control plane and data store; it is not implicitly a Worker and exposes no local filesystem.\n")
            append("Project is the logical scope for conversations, agents, prompts, and workspaces; it is not a filesystem path.\n")
            append("Conversation belongs to one Project and is not bound to a Workspace. Agent is its selected model and instruction configuration.\n")
            append("Worker is a named executor. Workspace is a logical filesystem resource. WorkspaceMount binds a Workspace to one worker-local root path and is the filesystem execution target.\n")
            append("Topology: ")
            append(topology)
            append("\n")
            append("Worker-scoped and workspace-scoped tool calls must include `$AI_TOOL_EXECUTION_TARGET_FIELD`; ")
            append("command-task operations route by task_id. ")
            append("Use only online worker IDs and online workspace mount IDs shown by this environment. ")
            append("If a required target is absent, offline, or ambiguous, explain that instead of guessing. ")
            append("Never infer that equal paths on different workers are the same workspace. ")
            append("Calls in one assistant response must target the same worker/mount; use separate responses otherwise. ")
            append("Failed or unavailable targets are never retried or reassigned automatically.\n")
            append("</execution_environment>")
        }
}

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

private fun JsonArray?.orEmpty(): JsonArray = this ?: buildJsonArray {}
