package com.gromozeka.application.service

import com.gromozeka.application.service.memory.MemoryEmbeddingIndexer
import com.gromozeka.application.service.memory.MemoryEmbeddingRebuildMode
import com.gromozeka.application.service.memory.MEMORY_EMBEDDING_STATUS_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_LIST_NAMESPACES_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_MAINTENANCE_TOOL_NAME
import com.gromozeka.application.service.memory.MemoryMaintenanceAction
import com.gromozeka.application.service.memory.MemoryMaintenanceQueue
import com.gromozeka.application.service.memory.MemoryOperationContext
import com.gromozeka.application.service.memory.MemoryOperationContextResolver
import com.gromozeka.application.service.memory.MemoryOperationQueue
import com.gromozeka.application.service.memory.MemoryToolResultRenderer
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryNamespaceSummary
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.shared.uuid.uuid7
import java.io.File
import klog.KLoggers
import org.springframework.stereotype.Service

@Service
class MemoryToolApplicationService(
    private val contextResolver: MemoryOperationContextResolver,
    private val projectService: ProjectDomainService,
    private val memoryOperationQueue: MemoryOperationQueue,
    private val memoryMaintenanceQueue: MemoryMaintenanceQueue,
    private val memoryEmbeddingIndexer: MemoryEmbeddingIndexer,
    private val memoryStore: MemoryStore,
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

    fun memoryQueueStatus(): String =
        MemoryToolResultRenderer.queueStatusJsonString(
            operationStatus = memoryOperationQueue.status(),
            maintenanceStatus = memoryMaintenanceQueue.status(),
            embeddingStatus = memoryEmbeddingIndexer.status(),
        )

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
            val result = memoryMaintenanceQueue.enqueue(
                action = action,
                targetKind = target.kind.toolName,
                targetValue = target.value,
                conversationId = context.conversationId,
                agent = context.agent,
                project = context.project,
                namespace = context.namespace,
                runtimeSystemPrompts = context.systemPrompts,
                runtimeTools = context.memoryTools,
                embeddingRebuildMode = embeddingRebuildMode,
            )

            log.info {
                "Memory maintenance tool queued: run=${result.runId.value} action=${action.toolName} " +
                    "target=${target.kind.toolName}:${target.value} namespace=${context.namespace.value} " +
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

    private fun resolveMaintenanceTarget(conversationIdValue: String?): MemoryMaintenanceTarget =
        conversationIdValue
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { MemoryMaintenanceTarget(MemoryMaintenanceTarget.Kind.CONVERSATION_ID, it) }
            ?: MemoryMaintenanceTarget(MemoryMaintenanceTarget.Kind.PROJECT_PATH, defaultStandaloneProjectPath())

    private suspend fun resolveMaintenanceContext(target: MemoryMaintenanceTarget): MemoryMaintenanceContext =
        when (target.kind) {
            MemoryMaintenanceTarget.Kind.CONVERSATION_ID -> contextResolver.resolveConversation(Conversation.Id(target.value))
                .toMaintenanceContext(Conversation.Id(target.value))

            MemoryMaintenanceTarget.Kind.PROJECT_PATH -> {
                val project = projectService.getOrCreate(File(target.value).absolutePath)
                project.toStandaloneMaintenanceContext()
            }
        }

    private suspend fun Project.toStandaloneMaintenanceContext(): MemoryMaintenanceContext {
        val context = contextResolver.resolveProject(this)
        return context.toMaintenanceContext(
            conversationId = Conversation.Id("memory_maintenance:standalone:${uuid7()}"),
        )
    }

    private fun MemoryOperationContext.toMaintenanceContext(
        conversationId: Conversation.Id,
    ): MemoryMaintenanceContext =
        MemoryMaintenanceContext(
            conversationId = conversationId,
            agent = agent,
            project = project,
            namespace = MemoryNamespace.Global,
            systemPrompts = systemPrompts,
            memoryTools = memoryTools,
        )

    private fun defaultStandaloneProjectPath(): String =
        contextResolver.defaultStandaloneProjectPath()

    private fun MemoryNamespace.emptyNamespaceSummaryIfMissing(existing: List<MemoryNamespaceSummary>): List<MemoryNamespaceSummary> {
        if (existing.any { it.namespace == this }) return emptyList()
        return listOf(MemoryNamespaceSummary(namespace = this))
    }

    private data class MemoryMaintenanceContext(
        val conversationId: Conversation.Id,
        val agent: AgentDefinition,
        val project: Project,
        val namespace: MemoryNamespace,
        val systemPrompts: List<String>,
        val memoryTools: List<AiToolCallback>,
    )

    private data class MemoryMaintenanceTarget(
        val kind: Kind,
        val value: String,
    ) {
        enum class Kind(val toolName: String) {
            CONVERSATION_ID("conversation_id"),
            PROJECT_PATH("project_path"),
        }
    }

}
