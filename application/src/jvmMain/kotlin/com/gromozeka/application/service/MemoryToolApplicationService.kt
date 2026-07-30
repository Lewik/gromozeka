package com.gromozeka.application.service

import com.gromozeka.application.service.memory.MemoryEmbeddingIndexer
import com.gromozeka.application.service.memory.MemoryEmbeddingRebuildMode
import com.gromozeka.application.service.memory.MEMORY_EMBEDDING_STATUS_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_LIST_NAMESPACES_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_MAINTENANCE_TOOL_NAME
import com.gromozeka.application.service.memory.MemoryMaintenanceAction
import com.gromozeka.application.service.memory.MemoryAsyncOperationApplicationService
import com.gromozeka.application.service.memory.MemoryMaintenanceTargetKind
import com.gromozeka.application.service.memory.ActiveMemoryOperation
import com.gromozeka.application.service.memory.MEMORY_OPERATION_KIND_METADATA_KEY
import com.gromozeka.application.service.memory.MEMORY_OPERATION_RUN_TYPES
import com.gromozeka.application.service.memory.MemoryOperationContextResolver
import com.gromozeka.application.service.memory.MemoryOperationKind
import com.gromozeka.application.service.memory.MemoryOperationQueueStatus
import com.gromozeka.application.service.memory.MemoryToolResultRenderer
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSummary
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.service.ConversationRuntimeExecutorDescriptor
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.server.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class MemoryToolApplicationService(
    private val conversationService: ConversationDomainService,
    private val workspaceService: WorkspaceDomainService,
    private val memoryOperations: MemoryAsyncOperationApplicationService,
    private val memoryEmbeddingIndexer: MemoryEmbeddingIndexer,
    private val memoryStore: MemoryStore,
    runtimeExecutorDescriptor: ConversationRuntimeExecutorDescriptor,
) {
    private val log = KLoggers.logger(this)
    private val runtimeExecutor = runtimeExecutorDescriptor.identity as? ConversationRuntimeExecutorIdentity.Server
        ?: error("Memory tools must run on Server")

    suspend fun memoryRunStatus(
        namespace: MemoryNamespace,
        runIdValue: String,
        includeChildren: Boolean = true,
        maxDepth: Int = 4,
    ): String =
        runCatching {
            val runId = MemoryRun.Id(runIdValue.trim())
            require(runId.value.isNotBlank()) { "memory_run_status requires non-blank run_id." }
            val rootRun = memoryStore.findRunById(runId)
                ?: return MemoryToolResultRenderer.failureJsonString("Memory run not found: ${runId.value}")
            require(rootRun.namespace == namespace) {
                "Memory run not found: ${runId.value}"
            }
            val boundedDepth = maxDepth.coerceIn(0, 8)
            val descendants = if (includeChildren) {
                loadRunDescendants(rootRun, boundedDepth)
                    .filter { it.namespace == namespace }
            } else {
                emptyList()
            }
            MemoryToolResultRenderer.runStatusJsonString(
                rootRun = rootRun,
                descendants = descendants,
                maxDepth = boundedDepth,
            )
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory run status failed.")
        }

    suspend fun memoryQueueStatus(namespace: MemoryNamespace): String =
        runCatching {
            val now = Clock.System.now()
            val unfinishedRuns = memoryStore.findRunsByStatuses(
                statuses = setOf(MemoryRun.Status.QUEUED, MemoryRun.Status.RUNNING),
                runTypes = MEMORY_OPERATION_RUN_TYPES,
            ).filter { it.namespace == namespace }
            val activeJobs = unfinishedRuns
                .filter { it.status == MemoryRun.Status.RUNNING }
                .sortedBy { it.startedAt }
                .map { run ->
                    ActiveMemoryOperation(
                        runId = run.id,
                        runType = run.runType,
                        operation = run.metadata[MEMORY_OPERATION_KIND_METADATA_KEY]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.let { operationName ->
                                MemoryOperationKind.entries.firstOrNull { it.wireName == operationName }
                            },
                        namespace = run.namespace,
                        startedAt = run.startedAt,
                        executionLease = run.executionLease,
                        leaseExpired = run.executionLease?.expiresAt?.let { it <= now } ?: true,
                    )
                }
            MemoryToolResultRenderer.queueStatusJsonString(
                MemoryOperationQueueStatus(
                    queuedJobs = unfinishedRuns.count { it.status == MemoryRun.Status.QUEUED },
                    activeJobs = activeJobs,
                    executor = runtimeExecutor,
                )
            )
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory queue status failed.")
        }

    suspend fun memoryEmbeddingStatus(
        namespace: MemoryNamespace,
        conversationIdValue: String? = null,
    ): String =
        runCatching {
            val target = resolveMaintenanceTarget(conversationIdValue)
            val context = resolveMaintenanceContext(target, namespace)
            val coverage = memoryEmbeddingIndexer.coverage(context.namespace)
            MemoryToolResultRenderer.embeddingCoverageResultJsonString(coverage)
        }.onFailure { error ->
            log.warn(error) {
                "Memory tool failed: tool=$MEMORY_EMBEDDING_STATUS_TOOL_NAME " +
                    "conversation=$conversationIdValue error=${error.message}"
            }
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory embedding status failed.")
        }

    suspend fun listNamespaces(namespace: MemoryNamespace): String =
        runCatching {
            val storedSummaries = memoryStore.listNamespaceSummaries()
            val summaries = storedSummaries
                .filter { it.namespace == namespace }
                .ifEmpty { listOf(MemoryNamespaceSummary(namespace = namespace)) }

            MemoryToolResultRenderer.namespaceListResultJsonString(
                summaries = summaries,
                defaultNamespace = namespace,
            )
        }.onFailure { error ->
            log.warn(error) { "Memory tool failed: tool=$MEMORY_LIST_NAMESPACES_TOOL_NAME error=${error.message}" }
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory namespace list failed.")
        }

    suspend fun runMaintenance(
        namespace: MemoryNamespace,
        actionValue: String,
        conversationIdValue: String? = null,
        embeddingRebuildModeValue: String? = null,
    ): String =
        runCatching {
            val action = MemoryMaintenanceAction.from(actionValue)
            val embeddingRebuildMode = MemoryEmbeddingRebuildMode.from(embeddingRebuildModeValue)
            val target = resolveMaintenanceTarget(conversationIdValue)
            val context = resolveMaintenanceContext(target, namespace)
            val result = memoryOperations.scheduleMaintenance(
                action = action,
                targetKind = target.kind,
                targetValue = target.value,
                executionConversationId = context.conversationId,
                namespace = context.namespace,
                embeddingRebuildMode = embeddingRebuildMode,
            )

            log.info {
                "Memory maintenance tool queued: run=${result.runId.value} action=${action.toolName} " +
                    "target=${target.kind.wireName}:${target.value} namespace=${context.namespace.value} " +
                    "embeddingMode=${embeddingRebuildMode.name.lowercase()} " +
                    "conversation=${context.conversationId.value} queueSize=${result.queueSize}"
            }
            MemoryToolResultRenderer.maintenanceQueuedResultJsonString(result)
        }.onFailure { error ->
            log.warn(error) {
                "Memory tool failed: tool=$MEMORY_MAINTENANCE_TOOL_NAME action=$actionValue " +
                    "conversation=$conversationIdValue error=${error.message}"
            }
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory maintenance failed.")
        }

    private suspend fun loadRunDescendants(
        rootRun: MemoryRun,
        maxDepth: Int,
    ): List<MemoryRun> {
        val visited = mutableSetOf(rootRun.id)
        val descendants = mutableListOf<MemoryRun>()
        var frontier = listOf(rootRun)

        repeat(maxDepth) {
            val next = frontier
                .flatMap { run -> loadDirectRunChildren(run) }
                .filter { run -> visited.add(run.id) }
            if (next.isEmpty()) {
                return descendants
            }
            descendants += next
            frontier = next
        }

        return descendants
    }

    private suspend fun loadDirectRunChildren(run: MemoryRun): List<MemoryRun> =
        (
            memoryStore.findRunsByParentRunId(run.id) +
                run.childRunIds.mapNotNull { childRunId -> memoryStore.findRunById(childRunId) }
            ).distinctBy { it.id }

    private suspend fun resolveMaintenanceTarget(conversationIdValue: String?): MemoryMaintenanceTarget =
        conversationIdValue
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { MemoryMaintenanceTarget(MemoryMaintenanceTargetKind.CONVERSATION_ID, it) }
            ?: MemoryMaintenanceTarget(MemoryMaintenanceTargetKind.STANDALONE, "standalone")

    private suspend fun resolveMaintenanceContext(
        target: MemoryMaintenanceTarget,
        namespace: MemoryNamespace,
    ): MemoryMaintenanceContext =
        when (target.kind) {
            MemoryMaintenanceTargetKind.CONVERSATION_ID -> {
                val conversationId = Conversation.Id(target.value)
                requireNotNull(conversationService.findById(conversationId)) {
                    "Conversation not found: ${conversationId.value}"
                }
                MemoryMaintenanceContext(
                    conversationId = conversationId,
                    namespace = namespace,
                )
            }

            MemoryMaintenanceTargetKind.WORKSPACE_ID -> {
                val workspaceId = Workspace.Id(target.value)
                requireNotNull(workspaceService.findById(workspaceId)) {
                    "Workspace not found: ${workspaceId.value}"
                }
                MemoryMaintenanceContext(
                    conversationId = Conversation.Id("memory_maintenance:standalone:${uuid7()}"),
                    namespace = namespace,
                )
            }

            MemoryMaintenanceTargetKind.STANDALONE -> {
                MemoryMaintenanceContext(
                    conversationId = Conversation.Id("memory_maintenance:standalone:${uuid7()}"),
                    namespace = namespace,
                )
            }
        }

    private data class MemoryMaintenanceContext(
        val conversationId: Conversation.Id,
        val namespace: MemoryNamespace,
    )

    private data class MemoryMaintenanceTarget(
        val kind: MemoryMaintenanceTargetKind,
        val value: String,
    )

}
