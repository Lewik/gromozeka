package com.gromozeka.application.service

import com.gromozeka.domain.model.AGENT_COLLABORATION_TOOL_NAMES
import com.gromozeka.domain.model.Conversation
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
import com.gromozeka.domain.tool.AiToolContextBindings
import com.gromozeka.domain.tool.AI_TOOL_INVOCATION_CONTEXT_FIELD
import com.gromozeka.domain.tool.withInvocationContext
import com.gromozeka.domain.tool.ToolAccessPolicy
import com.gromozeka.domain.tool.QualifiedToolName
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.contractFingerprint
import com.gromozeka.shared.utils.sha256
import kotlin.time.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.springframework.stereotype.Service

const val AI_TOOL_EXECUTION_WORKER_ID_FIELD = "worker_id"
const val AI_TOOL_EXECUTION_WORKSPACE_MOUNT_ID_FIELD = "workspace_mount_id"

private val CONVERSATION_PIN_TOOLS = setOf(
    "grz_conversation_pin_list", "grz_conversation_pin_get", "grz_conversation_pin_create",
    "grz_conversation_pin_update", "grz_conversation_pin_delete",
)

data class DistributedAiTool(
    val descriptor: AiToolDescriptor,
    val workers: List<DistributedAiToolWorker>,
    val logicalName: String = descriptor.definition.name,
    val modelName: String = descriptor.definition.name,
    val executionName: String = descriptor.definition.name,
    val contractFingerprint: String = descriptor.contractFingerprint(),
)

/** Unbound entry-point view; identity/revisions come from the SAME contract repository. */
data class AuthenticatedAiTool(
    val contract: com.gromozeka.domain.tool.AiToolContract,
    val descriptor: AiToolDescriptor,
    val workerIds: Set<ConversationRuntimeWorkerId>,
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
    /** Read current advertisements, never contact or wake a Worker. Access is rechecked on invocation. */
    suspend fun authenticatedTools(
        user: com.gromozeka.domain.model.User,
        include: (AiToolDescriptor) -> Boolean,
    ): List<AuthenticatedAiTool> {
        val accessible = workerAccessService.listAccessible(user).mapTo(mutableSetOf()) { it.id }
        val advertised = workerRegistry.list().filter { it.identity.workerId in accessible }
            .flatMap { registration ->
                registration.tools.filter { it.metadata.executionScope != AiToolExecutionScope.SERVER && include(it) }
                    .map { it to registration.identity.workerId }
            }
        val server = aiToolProvider.getTools().filter { it.available && it.metadata.executionScope == AiToolExecutionScope.SERVER }
            .map { AiToolDescriptor(it.definition, it.metadata) }.filter(include)
        val raw = (server + advertised.map { it.first }).distinct()
        val contracts = aiToolContractRepository.resolveAll(raw.map { it.asModelContractDescriptor() })
            .associateBy { it.fingerprint }
        return raw.map { descriptor ->
            AuthenticatedAiTool(
                contract = contracts.getValue(descriptor.asModelContractDescriptor().contractFingerprint()),
                descriptor = descriptor,
                workerIds = advertised.filter { it.first == descriptor }.mapTo(mutableSetOf()) { it.second },
            )
        }.sortedBy { it.contract.modelName }
    }

    suspend fun serverContracts() = aiToolContractRepository.resolveAll(aiToolProvider.getTools()
        .filter { it.available && it.metadata.executionScope == AiToolExecutionScope.SERVER }
        .map { AiToolDescriptor(it.definition, it.metadata).asModelContractDescriptor() })

    /** Resolve one current Server contract without rebuilding the Worker/workspace catalog. */
    suspend fun serverTool(name: QualifiedToolName, toolAccess: ToolAccessPolicy): AiToolCallback? {
        val callback = aiToolProvider.getTools().singleOrNull {
            it.available && it.metadata.executionScope == AiToolExecutionScope.SERVER &&
                it.definition.source == name.source && it.definition.name == name.name
        } ?: return null
        val contract = aiToolContractRepository.resolveAll(
            listOf(AiToolDescriptor(callback.definition, callback.metadata).asModelContractDescriptor())
        ).single()
        if (!toolAccess.allows(contract)) return null
        return modelCallback(DistributedAiTool(
            descriptor = contract.descriptor, workers = emptyList(), logicalName = contract.logicalName,
            modelName = contract.modelName, executionName = contract.logicalName,
            contractFingerprint = contract.fingerprint,
        ))
    }

    suspend fun snapshot(
        project: Project,
        toolAccess: ToolAccessPolicy = ToolAccessPolicy.DenyListed(),
        conversation: Conversation? = null,
    ): DistributedAiToolCatalogSnapshot = buildSnapshot(project, toolAccess, conversation, registerContracts = true)

    /** Uses registered contracts only; never allocates names or registers a tool. */
    suspend fun readSnapshot(
        project: Project,
        toolAccess: ToolAccessPolicy = ToolAccessPolicy.DenyListed(),
        conversation: Conversation? = null,
    ): DistributedAiToolCatalogSnapshot = buildSnapshot(project, toolAccess, conversation, registerContracts = false)

    private suspend fun buildSnapshot(
        project: Project,
        toolAccess: ToolAccessPolicy,
        conversation: Conversation?,
        registerContracts: Boolean,
    ): DistributedAiToolCatalogSnapshot {
        fun allowed(descriptor: AiToolDescriptor, logicalName: String, fingerprint: String): Boolean {
            val externalUnavailable = conversation?.externalChannel != null &&
                descriptor.definition.source == "gromozeka" &&
                (logicalName in AGENT_COLLABORATION_TOOL_NAMES || logicalName in CONVERSATION_PIN_TOOLS)
            return !externalUnavailable && toolAccess.allows(
                QualifiedToolName(descriptor.definition.source, logicalName), fingerprint)
        }
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
            .filter { (_, descriptor) -> registerContracts || allowed(
                descriptor, descriptor.definition.name, descriptor.contractFingerprint()) }
        val serverDescriptors = aiToolProvider.getTools()
            .asSequence()
            .filter(AiToolCallback::available)
            .filter { it.metadata.executionScope == AiToolExecutionScope.SERVER }
            .map { callback ->
                AiToolDescriptor(callback.definition, callback.metadata).asModelContractDescriptor()
            }
            .filter { descriptor -> registerContracts || allowed(
                descriptor, descriptor.definition.name, descriptor.contractFingerprint()) }
            .toList()
        val descriptors = workerAdvertisements.map { it.second } + serverDescriptors
        val contractsByFingerprint = if (registerContracts) {
            aiToolContractRepository.resolveAll(descriptors).associateBy { it.fingerprint }
        } else {
            val known = aiToolContractRepository.findAll().associateBy { it.fingerprint }
            descriptors.forEach { descriptor ->
                val contract = known[descriptor.contractFingerprint()]
                    ?: throw ContextSnapshotUnavailableException("A required tool contract is not registered yet")
                check(contract.descriptor == descriptor) { "Registered tool contract does not match its descriptor" }
            }
            known
        }
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
        val entries = (workerEntries + serverEntries).filterValues { entry ->
            allowed(entry.descriptor, entry.logicalName, entry.contractFingerprint)
        }.toSortedMap()
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
            )
        ).withInvocationContext(AiToolContextBindings.ConversationRuntime)

    private fun String.withExecutionTargetDescription(scope: AiToolExecutionScope): String {
        val targetDescription = when (scope) {
            AiToolExecutionScope.SERVER -> return this
            AiToolExecutionScope.WORKER ->
                "Select an exact compatible worker from the current execution environment in `$AI_TOOL_INVOCATION_CONTEXT_FIELD`."
            AiToolExecutionScope.WORKSPACE ->
                "Select the exact filesystem mount in `$AI_TOOL_INVOCATION_CONTEXT_FIELD`."
            AiToolExecutionScope.COMMAND_TASK_OWNER ->
                "The command task ID routes this call to the worker and mount that own the task."
            AiToolExecutionScope.COMMAND_MONITOR_OWNER ->
                "The command monitor ID routes this call to the worker and mount that own the monitor."
        }
        return "$this\n\n$targetDescription"
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
            append("Worker-scoped and workspace-scoped tool calls must include `$AI_TOOL_INVOCATION_CONTEXT_FIELD`; ")
            append("command-task operations route by task_id. ")
            append("Use only worker IDs and workspace mount IDs shown by this environment. Offline workers can receive queued requests within delivery_ttl_seconds. ")
            append("If a required target is absent or ambiguous, explain that instead of guessing. Waiting can end before execution; query the returned request ID instead of resubmitting an action. ")
            append("Never infer that equal paths on different workers are the same workspace. ")
            append("Independent tool calls may target different workers or workspace mounts in one assistant response and execute concurrently. ")
            append("Dependent tool calls must be issued in later assistant responses after observing prerequisite results. ")
            append("Failed or unavailable targets are never retried or reassigned automatically.\n")
            append("Versioned tool names are immutable contracts. Use the exact exposed name; routing removes only the reserved _grz field and preserves the original tool input.\n")
            append("</execution_environment>")
        }
}
