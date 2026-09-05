package com.gromozeka.application.service

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.repository.AiToolContractRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.contractFingerprint
import com.gromozeka.shared.utils.sha256
import kotlin.time.Clock
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
    val logicalName: String = descriptor.definition.name,
    val modelName: String = descriptor.definition.name,
    val executionName: String = descriptor.definition.name,
    val contractFingerprint: String = descriptor.contractFingerprint(),
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
    private val aiToolProvider: com.gromozeka.domain.service.AiToolProvider,
    private val workerAccessService: WorkerAccessService,
    private val aiToolContractRepository: AiToolContractRepository,
) {
    private val json = Json

    suspend fun snapshot(
        project: Project,
    ): DistributedAiToolCatalogSnapshot {
        val now = Clock.System.now()
        val staleBefore = now - ConversationRuntimeTiming.workerRegistrationStaleAfter
        val availableWorkerIds = workerAccessService.listAvailableToProject(project.id)
            .mapTo(mutableSetOf()) { it.id }
        val knownRegistrations = workerRegistry.list()
            .filter { it.identity.workerId in availableWorkerIds }
            .sortedBy { it.identity.workerId.value }
        val projectWorkspaces = workspaceService.findByProject(project.id)
            .associateBy { it.id }
        val projectMounts = projectWorkspaces.keys
            .flatMap { workspaceService.findMounts(it) }
            .filter { ConversationRuntimeWorkerId(it.workerId) in availableWorkerIds }
            .sortedBy { it.id.value }
        val mountsByWorker = knownRegistrations.associate { registration ->
            registration.identity.workerId to projectMounts
                .filter { it.workerId == registration.identity.workerId.value }
        }
        val workerAdvertisements = knownRegistrations
            .flatMap { registration ->
                registration.tools
                    .filter { it.metadata.executionScope != AiToolExecutionScope.SERVER }
                    .map { descriptor -> registration to descriptor.asModelContractDescriptor() }
            }
        val serverDescriptors = aiToolProvider.getTools()
            .asSequence()
            .filter(AiToolCallback::available)
            .filter { it.metadata.executionScope == AiToolExecutionScope.SERVER }
            .map { callback ->
                AiToolDescriptor(callback.definition, callback.metadata).asModelContractDescriptor()
            }
            .toList()
        val contractsByFingerprint = aiToolContractRepository.resolveAll(
            workerAdvertisements.map { it.second } + serverDescriptors
        ).associateBy { it.fingerprint }
        val workerEntries = workerAdvertisements
            .groupBy { (_, descriptor) -> descriptor.contractFingerprint() }
            .mapNotNull { (fingerprint, advertised) ->
                val contract = contractsByFingerprint.getValue(fingerprint)
                val descriptor = contract.descriptor
                val workers = advertised
                    .map { (registration, _) ->
                        DistributedAiToolWorker(
                            workerId = registration.identity.workerId,
                            workspaceMounts = mountsByWorker.getValue(registration.identity.workerId),
                        )
                    }
                    .filter {
                        descriptor.metadata.executionScope == AiToolExecutionScope.WORKER ||
                            descriptor.metadata.executionScope == AiToolExecutionScope.COMMAND_TASK_OWNER ||
                            descriptor.metadata.executionScope == AiToolExecutionScope.COMMAND_MONITOR_OWNER ||
                            it.workspaceMounts.isNotEmpty()
                    }
                    .sortedBy { it.workerId.value }
                workers.takeIf { it.isNotEmpty() }
                    ?.let {
                        contract.modelName to DistributedAiTool(
                            descriptor = descriptor,
                            workers = it,
                            logicalName = contract.logicalName,
                            modelName = contract.modelName,
                            executionName = contract.logicalName,
                            contractFingerprint = contract.fingerprint,
                        )
                    }
            }
            .toMap()
        val serverEntries = serverDescriptors
            .associate { descriptor ->
                val contract = contractsByFingerprint.getValue(descriptor.contractFingerprint())
                contract.modelName to DistributedAiTool(
                    descriptor = contract.descriptor,
                    workers = emptyList(),
                    logicalName = contract.logicalName,
                    modelName = contract.modelName,
                    executionName = contract.logicalName,
                    contractFingerprint = contract.fingerprint,
                )
            }
        val entries = (workerEntries + serverEntries).toSortedMap()
        val callbacks = entries.values.map(::modelCallback)
        val environmentTopology = buildEnvironmentTopology(
            project = project,
            knownRegistrations = knownRegistrations,
            staleBefore = staleBefore,
            projectWorkspaces = projectWorkspaces,
            projectMounts = projectMounts,
        )
        val toolContracts = buildJsonArray {
            entries.values.forEach { tool ->
                add(buildJsonObject {
                    put("model_name", tool.modelName)
                    put("execution_scope", tool.descriptor.metadata.executionScope.name.lowercase())
                    putJsonArray("compatible_worker_ids") {
                        tool.workers.forEach { worker -> add(JsonPrimitive(worker.workerId.value)) }
                    }
                })
            }
        }
        val environmentRevision = buildJsonObject {
            put("topology", environmentTopology)
            put("tool_contracts", toolContracts)
        }.toString().sha256()

        return DistributedAiToolCatalogSnapshot(
            tools = callbacks,
            entries = entries,
            registrations = knownRegistrations,
            environmentRevision = environmentRevision,
            environmentPrompt = buildEnvironmentPrompt(
                revision = environmentRevision,
                topology = environmentTopology,
                toolContracts = toolContracts,
                workerEnvironmentToolName = entries.values
                    .firstOrNull { it.logicalName == "grz_get_worker_environment" }
                    ?.modelName,
            ),
        )
    }

    private fun modelCallback(tool: DistributedAiTool): AiToolCallback =
        object : AiToolCallback {
            override val definition: AiToolDefinition = tool.descriptor.definition.copy(
                name = tool.modelName,
            )
            override val metadata: AiToolMetadata = tool.descriptor.metadata

            override fun call(toolInput: String, context: ToolExecutionContext?): String =
                error("Distributed tool descriptors cannot execute locally")
        }

    private fun AiToolDescriptor.asModelContractDescriptor(): AiToolDescriptor =
        copy(
            definition = definition.copy(
                description = definition.description.withExecutionTargetDescription(metadata.executionScope),
                inputSchema = definition.inputSchema.withExecutionTargetSchema(
                    toolName = definition.name,
                    scope = metadata.executionScope,
                ),
            )
        )

    private fun String.withExecutionTargetDescription(scope: AiToolExecutionScope): String {
        val targetDescription = when (scope) {
            AiToolExecutionScope.SERVER -> return this
            AiToolExecutionScope.WORKER ->
                "Select an exact compatible worker from the current execution environment in `$AI_TOOL_EXECUTION_TARGET_FIELD`."
            AiToolExecutionScope.WORKSPACE ->
                "Select the exact filesystem mount in `$AI_TOOL_EXECUTION_TARGET_FIELD`."
            AiToolExecutionScope.COMMAND_TASK_OWNER ->
                "The command task ID routes this call to the worker and mount that own the task."
            AiToolExecutionScope.COMMAND_MONITOR_OWNER ->
                "The command monitor ID routes this call to the worker and mount that own the monitor."
        }
        return "$this\n\n$targetDescription"
    }

    private fun String.withExecutionTargetSchema(
        toolName: String,
        scope: AiToolExecutionScope,
    ): String {
        val schema = json.parseToJsonElement(this).jsonObject
        val properties = schema["properties"]?.jsonObject.orEmpty()
        check(AI_TOOL_EXECUTION_TARGET_FIELD !in properties) {
            "Tool '$toolName' already declares reserved field " +
                "'$AI_TOOL_EXECUTION_TARGET_FIELD'"
        }
        if (
            scope == AiToolExecutionScope.SERVER ||
            scope == AiToolExecutionScope.COMMAND_TASK_OWNER ||
            scope == AiToolExecutionScope.COMMAND_MONITOR_OWNER
        ) {
            return schema.toString()
        }
        val required = schema["required"]?.jsonArray.orEmpty()
            .map { it.jsonPrimitive.content }
            .toMutableSet()
            .apply { add(AI_TOOL_EXECUTION_TARGET_FIELD) }
        val workspaceRequired = scope == AiToolExecutionScope.WORKSPACE

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
                putJsonObject("delivery_ttl_seconds") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 604800)
                    put("description", "Time allowed before execution starts, including offline delivery. Default 30 seconds. Expired requests never start.")
                }
                putJsonObject("execution_timeout_seconds") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 86400)
                    put("description", "Execution limit after starting, independent of delivery TTL. Default 1800 seconds.")
                }
                putJsonObject("wait_timeout_seconds") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 604800)
                    put("description", "How long this call waits. Default delivery TTL plus execution timeout, capped at 7 days. Ending the wait does not cancel execution; use grz_worker_request_get or grz_worker_request_cancel with the returned request ID.")
                }
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
        staleBefore: kotlin.time.Instant,
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
                            putJsonObject("environment_profile") {
                                put("observed_at", it.environmentProfile.observedAt.toString())
                                put("os_family", it.environmentProfile.operatingSystem.family.name.lowercase())
                                put("os_name", it.environmentProfile.operatingSystem.name)
                                put("os_version", it.environmentProfile.operatingSystem.version)
                                put("architecture", it.environmentProfile.architecture)
                                put("shell_kind", it.environmentProfile.nativeShell.kind.name.lowercase())
                                put("shell_executable", it.environmentProfile.nativeShell.executable)
                                put("timezone_id", it.environmentProfile.timezoneId)
                                put("locale_tag", it.environmentProfile.localeTag)
                                put("logical_processor_count", it.environmentProfile.logicalProcessorCount)
                                it.environmentProfile.totalMemoryBytes?.let { bytes ->
                                    put("total_memory_bytes", bytes)
                                }
                                putJsonArray("available_executables") {
                                    it.environmentProfile.availableExecutables.forEach { executable ->
                                        add(JsonPrimitive(executable))
                                    }
                                }
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
        toolContracts: JsonArray,
        workerEnvironmentToolName: String?,
    ): String =
        buildString {
            append("<execution_environment revision=\"")
            append(revision)
            append("\">\n")
            append("Server is the canonical control plane and data store; it is not implicitly a Worker and exposes no local filesystem.\n")
            append("Project is the logical scope for conversations, agents, prompts, and workspaces; it is not a filesystem path.\n")
            append("Conversation belongs to one Project and is not bound to a Workspace. Agent is its selected model and instruction configuration.\n")
            append("Worker is a named executor. Workspace is a logical filesystem resource. WorkspaceMount binds a Workspace to one worker-local root path and is the filesystem execution target.\n")
            append("Each worker environment_profile is the stable profile advertised when that worker session started")
            workerEnvironmentToolName?.let { toolName ->
                append("; when available, use ")
                append(toolName)
                append(" if current process, capacity, storage, or executable data matters")
            }
            append(".\n")
            append("Topology: ")
            append(topology)
            append("\n")
            append("Active tool routes: ")
            append(toolContracts)
            append("\n")
            append("Worker-scoped and workspace-scoped tool calls must include `$AI_TOOL_EXECUTION_TARGET_FIELD`; ")
            append("command-task operations route by task_id. ")
            append("Use only worker IDs and workspace mount IDs shown by this environment. Offline workers can receive queued requests within delivery_ttl_seconds. ")
            append("If a required target is absent or ambiguous, explain that instead of guessing. Waiting can end before execution; query the returned request ID instead of resubmitting an action. ")
            append("Never infer that equal paths on different workers are the same workspace. ")
            append("Independent tool calls may target different workers or workspace mounts in one assistant response and execute concurrently. ")
            append("Dependent tool calls must be issued in later assistant responses after observing prerequisite results. ")
            append("Failed or unavailable targets are never retried or reassigned automatically.\n")
            append("Versioned tool names are immutable contracts. Use the exact exposed name; routing removes only the reserved execution_target field and preserves the original tool input.\n")
            append("</execution_environment>")
        }
}

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

private fun JsonArray?.orEmpty(): JsonArray = this ?: buildJsonArray {}
