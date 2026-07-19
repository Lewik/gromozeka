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
const val AI_TOOL_EXECUTION_WORKSPACE_ID_FIELD = "workspace_id"

data class DistributedAiTool(
    val descriptor: AiToolDescriptor,
    val workers: List<DistributedAiToolWorker>,
)

data class DistributedAiToolWorker(
    val workerId: ConversationRuntimeWorkerId,
    val workspaceIds: Set<Workspace.Id>,
)

data class DistributedAiToolCatalogSnapshot(
    val tools: List<AiToolCallback>,
    val entries: Map<String, DistributedAiTool>,
    val registrations: List<ConversationRuntimeWorkerRegistration>,
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
        workspace: Workspace,
    ): DistributedAiToolCatalogSnapshot {
        require(workspace.projectId == project.id) {
            "Current workspace '${workspace.id.value}' does not belong to project '${project.id.value}'"
        }
        val now = Clock.System.now()
        val registrations = workerRegistry.list()
            .filter { it.isOnline(now - ConversationRuntimeTiming.workerRegistrationStaleAfter) }
            .sortedBy { it.identity.workerId.value }
        val projectWorkspaces = workspaceService.findByProject(project.id)
            .associateBy { it.id }
        check(workspace.id in projectWorkspaces) {
            "Current workspace '${workspace.id.value}' is not present in project '${project.id.value}'"
        }
        val mountsByWorker = registrations.associate { registration ->
            registration.identity.workerId to workspaceService
                .findMountsByWorker(registration.identity.workerId.value)
                .filter { it.workspaceId in projectWorkspaces }
        }
        val entries = registrations
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
                            workspaceIds = mountsByWorker
                                .getValue(registration.identity.workerId)
                                .mapTo(mutableSetOf()) { it.workspaceId },
                        )
                    }
                    .filter {
                        descriptor.metadata.executionScope == AiToolExecutionScope.WORKER ||
                            it.workspaceIds.isNotEmpty()
                    }
                    .sortedBy { it.workerId.value }
                workers.takeIf { it.isNotEmpty() }
                    ?.let { toolName to DistributedAiTool(descriptor, it) }
            }
            .toMap()
            .toSortedMap()
        val callbacks = entries.values.map(::modelCallback)

        return DistributedAiToolCatalogSnapshot(
            tools = callbacks,
            entries = entries,
            registrations = registrations,
            environmentPrompt = buildEnvironmentPrompt(
                project = project,
                currentWorkspace = workspace,
                registrations = registrations,
                projectWorkspaces = projectWorkspaces,
                mountsByWorker = mountsByWorker,
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
            AiToolExecutionScope.WORKER ->
                "Select the exact online worker in `$AI_TOOL_EXECUTION_TARGET_FIELD`."
            AiToolExecutionScope.WORKSPACE ->
                "Select the exact online worker and filesystem workspace in `$AI_TOOL_EXECUTION_TARGET_FIELD`."
            AiToolExecutionScope.COMMAND_TASK_OWNER ->
                "Select the exact worker and filesystem workspace that own the command task in " +
                    "`$AI_TOOL_EXECUTION_TARGET_FIELD`."
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
        val required = schema["required"]?.jsonArray.orEmpty()
            .map { it.jsonPrimitive.content }
            .toMutableSet()
            .apply { add(AI_TOOL_EXECUTION_TARGET_FIELD) }
        val workspaceRequired = tool.descriptor.metadata.executionScope != AiToolExecutionScope.WORKER
        val workerIds = tool.workers.map { it.workerId.value }.distinct().sorted()
        val workspaceIds = tool.workers.flatMap { it.workspaceIds }.map { it.value }.distinct().sorted()
        val exactTargets = tool.workers.flatMap { worker ->
            if (workspaceRequired) {
                worker.workspaceIds
                    .sortedBy { it.value }
                    .map { workspaceId -> worker.workerId.value to workspaceId.value }
            } else {
                listOf(worker.workerId.value to null)
            }
        }
        check(exactTargets.isNotEmpty()) {
            "Tool '${tool.descriptor.definition.name}' has no valid execution targets"
        }

        val targetSchema = buildJsonObject {
            put("type", "object")
            put(
                "description",
                if (workspaceRequired) {
                    "Exact worker and filesystem workspace pair for this call."
                } else {
                    "Exact worker for this call."
                }
            )
            putJsonObject("properties") {
                putJsonObject(AI_TOOL_EXECUTION_WORKER_ID_FIELD) {
                    put("type", "string")
                    put("description", "Exact ID of the worker that must execute this tool call.")
                    putJsonArray("enum") {
                        workerIds.sorted().forEach { add(JsonPrimitive(it)) }
                    }
                }
                if (workspaceRequired) {
                    putJsonObject(AI_TOOL_EXECUTION_WORKSPACE_ID_FIELD) {
                        put("type", "string")
                        put(
                            "description",
                            "Exact filesystem workspace ID mounted by the selected worker."
                        )
                        putJsonArray("enum") {
                            workspaceIds.forEach { add(JsonPrimitive(it)) }
                        }
                    }
                }
            }
            if (workspaceRequired) {
                putJsonArray("oneOf") {
                    exactTargets.forEach { (workerId, workspaceId) ->
                        add(
                            buildJsonObject {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject(AI_TOOL_EXECUTION_WORKER_ID_FIELD) {
                                        putJsonArray("enum") { add(JsonPrimitive(workerId)) }
                                    }
                                    putJsonObject(AI_TOOL_EXECUTION_WORKSPACE_ID_FIELD) {
                                        putJsonArray("enum") { add(JsonPrimitive(checkNotNull(workspaceId))) }
                                    }
                                }
                                putJsonArray("required") {
                                    add(JsonPrimitive(AI_TOOL_EXECUTION_WORKER_ID_FIELD))
                                    add(JsonPrimitive(AI_TOOL_EXECUTION_WORKSPACE_ID_FIELD))
                                }
                            }
                        )
                    }
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive(AI_TOOL_EXECUTION_WORKER_ID_FIELD))
                if (workspaceRequired) {
                    add(JsonPrimitive(AI_TOOL_EXECUTION_WORKSPACE_ID_FIELD))
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

    private suspend fun buildEnvironmentPrompt(
        project: Project,
        currentWorkspace: Workspace,
        registrations: List<ConversationRuntimeWorkerRegistration>,
        projectWorkspaces: Map<Workspace.Id, Workspace>,
        mountsByWorker: Map<ConversationRuntimeWorkerId, List<WorkspaceMount>>,
    ): String {
        return buildString {
            append("<execution_environment>\n")
            append("A Project is a logical container. A Filesystem Workspace is one concrete checkout or tree ")
            append("inside a project. A Worker is a named, shared execution process. Paths are worker-local mount paths.\n")
            append("Current project: id=")
            append(project.id.value)
            append(" name=")
            append(project.name)
            append("\nCurrent workspace: id=")
            append(currentWorkspace.id.value)
            append(" name=")
            append(currentWorkspace.name)
            append(" kind=")
            append(currentWorkspace.kind.name)
            append("\nOnline workers:\n")
            if (registrations.isEmpty()) {
                append("- none\n")
            } else {
                registrations.forEach { registration ->
                    val mounts = mountsByWorker.getValue(registration.identity.workerId)
                    append("- worker_id=")
                    append(registration.identity.workerId.value)
                    append(" version=")
                    append(registration.version)
                    append(" workspaces=")
                    if (mounts.isEmpty()) {
                        append("none")
                    } else {
                        append(
                            mounts
                                .sortedBy { it.workspaceId.value }
                                .joinToString(prefix = "[", postfix = "]") { mount ->
                                    val workspace = projectWorkspaces[mount.workspaceId]
                                    buildString {
                                        append("{workspace_id=")
                                        append(mount.workspaceId.value)
                                        workspace?.let {
                                            append(", project_id=")
                                            append(it.projectId.value)
                                            append(", name=")
                                            append(it.name)
                                        }
                                        append(", root_path=")
                                        append(mount.rootPath)
                                        append("}")
                                    }
                                }
                        )
                    }
                    append("\n")
                }
            }
            append("Every tool call must include `$AI_TOOL_EXECUTION_TARGET_FIELD`. ")
            append("Use only worker/workspace pairs shown by the tool schema and this environment. ")
            append("Never infer that equal paths on different workers are the same workspace. ")
            append("Calls in one assistant response must target the same worker/workspace; use separate responses otherwise. ")
            append("Failed or unavailable targets are never retried or reassigned automatically.\n")
            append("</execution_environment>")
        }
    }
}

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

private fun JsonArray?.orEmpty(): JsonArray = this ?: buildJsonArray {}
