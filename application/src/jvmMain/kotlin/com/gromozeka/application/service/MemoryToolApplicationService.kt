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
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSummary
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.shared.uuid.uuid7
import java.io.File
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.stereotype.Service

@Service
class MemoryToolApplicationService(
    private val contextResolver: MemoryOperationContextResolver,
    private val projectService: ProjectDomainService,
    private val memoryOperations: MemoryAsyncOperationApplicationService,
    private val memoryEmbeddingIndexer: MemoryEmbeddingIndexer,
    private val memoryStore: MemoryStore,
    private val runtimeWorkerRegistry: ConversationRuntimeWorkerRegistry,
) {
    private val log = KLoggers.logger(this)

    suspend fun memoryRunStatus(
        runIdValue: String,
        includeChildren: Boolean = true,
        maxDepth: Int = 4,
    ): String =
        runCatching {
            val runId = MemoryRun.Id(runIdValue.trim())
            require(runId.value.isNotBlank()) { "memory_run_status requires non-blank run_id." }
            val rootRun = memoryStore.findRunById(runId)
                ?: return MemoryToolResultRenderer.failureJsonString("Memory run not found: ${runId.value}")
            val boundedDepth = maxDepth.coerceIn(0, 8)
            val descendants = if (includeChildren) {
                loadRunDescendants(rootRun, boundedDepth)
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

    suspend fun memoryQueueStatus(): String =
        runCatching {
            val now = Clock.System.now()
            val unfinishedRuns = memoryStore.findRunsByStatuses(
                statuses = setOf(MemoryRun.Status.QUEUED, MemoryRun.Status.RUNNING),
                runTypes = MEMORY_OPERATION_RUN_TYPES,
            )
            val onlineWorkers = runtimeWorkerRegistry.list()
                .filter { registration ->
                    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE in registration.capabilities &&
                        registration.isOnline(now - ConversationRuntimeTiming.workerRegistrationStaleAfter)
                }
                .sortedBy { it.identity.workerId.value }
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
                    onlineWorkers = onlineWorkers,
                )
            )
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory queue status failed.")
        }

    suspend fun memoryEmbeddingStatus(
        conversationIdValue: String? = null,
    ): String =
        runCatching {
            val target = resolveMaintenanceTarget(conversationIdValue)
            val context = resolveMaintenanceContext(target)
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

    suspend fun listNamespaces(): String =
        runCatching {
            val storedSummaries = memoryStore.listNamespaceSummaries()
            val summaries = (storedSummaries + MemoryNamespace.Global.emptyNamespaceSummaryIfMissing(storedSummaries))
                .distinctBy { it.namespace.value }
                .sortedWith(compareByDescending<MemoryNamespaceSummary> { it.namespace == MemoryNamespace.Global }.thenBy { it.namespace.value })

            MemoryToolResultRenderer.namespaceListResultJsonString(
                summaries = summaries,
                defaultNamespace = MemoryNamespace.Global,
            )
        }.onFailure { error ->
            log.warn(error) { "Memory tool failed: tool=$MEMORY_LIST_NAMESPACES_TOOL_NAME error=${error.message}" }
        }.getOrElse { error ->
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory namespace list failed.")
        }

    suspend fun runMaintenance(
        actionValue: String,
        conversationIdValue: String? = null,
        embeddingRebuildModeValue: String? = null,
    ): String =
        runCatching {
            val action = MemoryMaintenanceAction.from(actionValue)
            val embeddingRebuildMode = MemoryEmbeddingRebuildMode.from(embeddingRebuildModeValue)
            val target = resolveMaintenanceTarget(conversationIdValue)
            val context = resolveMaintenanceContext(target)
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
            ?: defaultStandaloneProjectPath()
                .let { path -> projectService.getOrCreate(File(path).absolutePath) }
                .let { project -> MemoryMaintenanceTarget(MemoryMaintenanceTargetKind.PROJECT_ID, project.id.value) }

    private suspend fun resolveMaintenanceContext(target: MemoryMaintenanceTarget): MemoryMaintenanceContext =
        when (target.kind) {
            MemoryMaintenanceTargetKind.CONVERSATION_ID -> contextResolver.resolveConversation(Conversation.Id(target.value))
                .let {
                    MemoryMaintenanceContext(
                        conversationId = Conversation.Id(target.value),
                        namespace = MemoryNamespace.Global,
                    )
                }

            MemoryMaintenanceTargetKind.PROJECT_ID -> {
                contextResolver.resolveProjectId(Project.Id(target.value))
                MemoryMaintenanceContext(
                    conversationId = Conversation.Id("memory_maintenance:standalone:${uuid7()}"),
                    namespace = MemoryNamespace.Global,
                )
            }
        }

    private fun defaultStandaloneProjectPath(): String =
        contextResolver.defaultStandaloneProjectPath()

    private fun MemoryNamespace.emptyNamespaceSummaryIfMissing(existing: List<MemoryNamespaceSummary>): List<MemoryNamespaceSummary> {
        if (existing.any { it.namespace == this }) return emptyList()
        return listOf(MemoryNamespaceSummary(namespace = this))
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
