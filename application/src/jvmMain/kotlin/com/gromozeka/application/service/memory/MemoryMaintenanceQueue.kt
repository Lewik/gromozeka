package com.gromozeka.application.service.memory

import com.gromozeka.application.service.MemoryApplicationService
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.shared.uuid.uuid7
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

@Service
class MemoryMaintenanceQueue(
    private val memoryStore: MemoryStore,
    private val memoryApplicationService: MemoryApplicationService,
    private val embeddingIndexer: MemoryEmbeddingIndexer,
    @Qualifier("supervisorScope") private val coroutineScope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private val jobs = Channel<MemoryMaintenanceJob>(Channel.UNLIMITED)
    private val queuedJobs = AtomicInteger(0)
    private val activeJob = AtomicReference<ActiveMemoryMaintenanceJob?>(null)
    private val totalEnqueuedJobs = AtomicLong(0)
    private val totalStartedJobs = AtomicLong(0)
    private val totalCompletedJobs = AtomicLong(0)
    private val totalFatallyFailedJobs = AtomicLong(0)

    init {
        coroutineScope.launch {
            for (job in jobs) {
                queuedJobs.updateAndGet { (it - 1).coerceAtLeast(0) }
                val startedAt = Clock.System.now()
                activeJob.set(job.toActiveJob(startedAt))
                totalStartedJobs.incrementAndGet()
                try {
                    runCatching {
                        process(job, startedAt)
                    }.onSuccess {
                        totalCompletedJobs.incrementAndGet()
                    }.onFailure { error ->
                        totalFatallyFailedJobs.incrementAndGet()
                        failJob(job, error, startedAt)
                    }
                } finally {
                    activeJob.set(null)
                }
            }
        }
    }

    suspend fun enqueue(
        action: MemoryMaintenanceAction,
        targetKind: String,
        targetValue: String,
        conversationId: Conversation.Id,
        agent: AgentDefinition,
        project: Project,
        namespace: MemoryNamespace,
        runtimeSystemPrompts: List<String>,
        runtimeTools: List<AiToolCallback>,
    ): MemoryMaintenanceQueuedResult {
        val now = Clock.System.now()
        val run = MemoryRun(
            id = MemoryRun.Id("maintenance:${action.toolName}:run:${uuid7()}"),
            namespace = namespace,
            runType = action.runType,
            triggerMode = MemoryRun.TriggerMode.MANUAL,
            summary = "${action.displayName} queued",
            progress = MemoryRun.Progress(totalUnits = 1),
            output = buildJsonObject {
                put("action", action.toolName)
                put("target_kind", targetKind)
                put("target_value", targetValue)
                put("conversation_id", conversationId.value)
            },
            metadata = buildJsonObject {
                put("queue", "memory_maintenance")
                put("action", action.toolName)
                put("targetKind", targetKind)
                put("targetValue", targetValue)
            },
            status = MemoryRun.Status.QUEUED,
            createdAt = now,
        )
        memoryStore.apply(MemoryUpdateBatch(runs = listOf(run)))

        queuedJobs.incrementAndGet()
        totalEnqueuedJobs.incrementAndGet()
        val job = MemoryMaintenanceJob(
            run = run,
            action = action,
            targetKind = targetKind,
            targetValue = targetValue,
            conversationId = conversationId,
            agent = agent,
            project = project,
            namespace = namespace,
            runtimeSystemPrompts = runtimeSystemPrompts,
            runtimeTools = runtimeTools,
        )
        val result = jobs.trySend(job)
        if (result.isFailure) {
            queuedJobs.updateAndGet { (it - 1).coerceAtLeast(0) }
            totalEnqueuedJobs.updateAndGet { (it - 1).coerceAtLeast(0L) }
            val error = result.exceptionOrNull() ?: IllegalStateException("Memory maintenance queue rejected job.")
            markRunFailed(run, error, now)
            throw error
        }

        val queueSize = queuedJobs.get()
        log.info {
            "Memory maintenance queued: run=${run.id.value} action=${action.toolName} namespace=${namespace.value} " +
                "target=$targetKind:$targetValue queueSize=$queueSize"
        }
        return MemoryMaintenanceQueuedResult(
            runId = run.id,
            action = action,
            targetKind = targetKind,
            targetValue = targetValue,
            namespace = namespace,
            conversationId = conversationId,
            queueSize = queueSize,
        )
    }

    fun status(): MemoryMaintenanceQueueStatus =
        MemoryMaintenanceQueueStatus(
            pendingJobs = queuedJobs.get(),
            activeJob = activeJob.get(),
            totalEnqueuedJobs = totalEnqueuedJobs.get(),
            totalStartedJobs = totalStartedJobs.get(),
            totalCompletedJobs = totalCompletedJobs.get(),
            totalFatallyFailedJobs = totalFatallyFailedJobs.get(),
        )

    private suspend fun process(
        job: MemoryMaintenanceJob,
        startedAt: Instant,
    ) {
        var parentRun = job.run.copy(
            status = MemoryRun.Status.RUNNING,
            startedAt = startedAt,
            summary = "${job.action.displayName} running",
            progress = MemoryRun.Progress(
                totalUnits = 1,
                currentUnitLabel = job.action.toolName,
            ),
        )
        persist(parentRun)

        log.info {
            "Memory maintenance started: run=${parentRun.id.value} action=${job.action.toolName} namespace=${job.namespace.value} " +
                "target=${job.targetKind}:${job.targetValue}"
        }

        val result = execute(job)
        val completedAt = Clock.System.now()
        parentRun = parentRun.copy(
            status = MemoryRun.Status.SUCCESS,
            childRunIds = result.memoryBatch.runs.map { it.id }.distinct(),
            progress = MemoryRun.Progress(totalUnits = 1, completedUnits = 1),
            summary = result.summary.ifBlank { "${job.action.displayName} completed" },
            output = result.toRunOutput(job),
            latencyMs = completedAt.toEpochMilliseconds() - startedAt.toEpochMilliseconds(),
            completedAt = completedAt,
        )
        persist(parentRun)

        log.info {
            "Memory maintenance completed: run=${parentRun.id.value} action=${job.action.toolName} namespace=${job.namespace.value} " +
                "childRuns=${parentRun.childRunIds.size} latencyMs=${parentRun.latencyMs} summary=${parentRun.summary.oneLineForMaintenanceQueueLog()}"
        }
    }

    private suspend fun execute(job: MemoryMaintenanceJob): MemoryMaintenanceExecutionResult =
        when (job.action) {
            MemoryMaintenanceAction.CONSOLIDATE -> {
                val result = memoryApplicationService.runNoteConsolidation(
                    conversationId = job.conversationId,
                    agent = job.agent,
                    project = job.project,
                    runtimeSystemPrompts = job.runtimeSystemPrompts,
                    runtimeTools = job.runtimeTools,
                    namespace = job.namespace,
                )
                MemoryMaintenanceExecutionResult(
                    summary = result.consolidationResult.summary,
                    memoryBatch = result.memoryBatch,
                    details = buildJsonObject {
                        put("selected_notes", result.selectedNotes.size)
                        put("related_hits", result.relatedHits.size)
                        put("raw_claim_candidates", result.rawConsolidationResult.claimCandidates.size)
                        put("final_claim_candidates", result.consolidationResult.claimCandidates.size)
                        put("raw_note_actions", result.rawConsolidationResult.noteActions.size)
                        put("final_note_actions", result.consolidationResult.noteActions.size)
                        put("raw_task_actions", result.rawConsolidationResult.taskActions.size)
                        put("final_task_actions", result.consolidationResult.taskActions.size)
                        put("raw_episode_candidates", result.rawConsolidationResult.episodeCandidates.size)
                        put("final_episode_candidates", result.consolidationResult.episodeCandidates.size)
                    },
                )
            }

            MemoryMaintenanceAction.REPAIR -> {
                val result = memoryApplicationService.runMemoryRepair(
                    conversationId = job.conversationId,
                    agent = job.agent,
                    project = job.project,
                    runtimeSystemPrompts = job.runtimeSystemPrompts,
                    runtimeTools = job.runtimeTools,
                    namespace = job.namespace,
                )
                MemoryMaintenanceExecutionResult(
                    summary = result.repairPlan.summary,
                    memoryBatch = result.memoryBatch,
                    details = buildJsonObject {
                        put("candidate_clusters", result.candidateClusters.size)
                        put("suspicious_hits", result.suspiciousHits.size)
                        put("repair_actions", result.repairPlan.repairActions.size)
                    },
                )
            }

            MemoryMaintenanceAction.MAINTAIN_ENTITIES -> {
                val result = memoryApplicationService.runEntityMaintenance(
                    conversationId = job.conversationId,
                    agent = job.agent,
                    project = job.project,
                    runtimeSystemPrompts = job.runtimeSystemPrompts,
                    runtimeTools = job.runtimeTools,
                    namespace = job.namespace,
                )
                MemoryMaintenanceExecutionResult(
                    summary = result.maintenancePlan.summary,
                    memoryBatch = result.memoryBatch,
                    details = buildJsonObject {
                        put("candidate_groups", result.candidateGroups.size)
                        put("maintenance_actions", result.maintenancePlan.actions.size)
                    },
                )
            }

            MemoryMaintenanceAction.APPLY_RETENTION -> {
                val result = memoryApplicationService.runRetention(
                    conversationId = job.conversationId,
                    project = job.project,
                    namespace = job.namespace,
                )
                MemoryMaintenanceExecutionResult(
                    summary = result.retentionPlan.summary,
                    memoryBatch = result.memoryBatch,
                    details = buildJsonObject {
                        put("candidates", result.candidates.size)
                        put("retention_actions", result.retentionPlan.retentionActions.size)
                    },
                )
            }

            MemoryMaintenanceAction.REBUILD_EMBEDDINGS -> {
                val result = embeddingIndexer.rebuildNamespace(job.namespace)
                MemoryMaintenanceExecutionResult(
                    summary = result.summary,
                    memoryBatch = result.memoryBatch,
                    details = buildJsonObject {
                        put("model_configuration_id", result.modelConfigurationId)
                        put("provider_model_id", result.providerModelId)
                        put("dimensions", result.dimensions)
                        put("embeddable_items", result.embeddableItems)
                        put("embeddings", result.embeddings)
                    },
                )
            }
        }

    private suspend fun failJob(
        job: MemoryMaintenanceJob,
        error: Throwable,
        startedAt: Instant,
    ) {
        markRunFailed(job.run, error, startedAt)
        log.warn(error) {
            "Memory maintenance failed: run=${job.run.id.value} action=${job.action.toolName} namespace=${job.namespace.value} " +
                "target=${job.targetKind}:${job.targetValue} error=${error.message}"
        }
    }

    private suspend fun markRunFailed(
        run: MemoryRun,
        error: Throwable,
        startedAt: Instant,
    ) {
        val completedAt = Clock.System.now()
        persist(
            run.copy(
                status = MemoryRun.Status.FAILED,
                startedAt = run.startedAt ?: startedAt,
                summary = "Memory maintenance failed: ${error.message ?: error::class.simpleName.orEmpty()}",
                errorText = error.message ?: error::class.simpleName.orEmpty(),
                progress = run.progress ?: MemoryRun.Progress(totalUnits = 1),
                latencyMs = completedAt.toEpochMilliseconds() - startedAt.toEpochMilliseconds(),
                completedAt = completedAt,
            )
        )
    }

    private suspend fun persist(run: MemoryRun) {
        memoryStore.apply(MemoryUpdateBatch(runs = listOf(run)))
    }
}

enum class MemoryMaintenanceAction(
    val toolName: String,
    val displayName: String,
    val runType: MemoryRun.Type,
) {
    CONSOLIDATE("consolidate", "Memory consolidation", MemoryRun.Type.CONSOLIDATE_NOTES),
    REPAIR("repair", "Memory repair", MemoryRun.Type.REPAIR_MEMORY),
    MAINTAIN_ENTITIES("maintain_entities", "Memory entity maintenance", MemoryRun.Type.MAINTAIN_ENTITIES),
    APPLY_RETENTION("apply_retention", "Memory retention", MemoryRun.Type.APPLY_RETENTION),
    REBUILD_EMBEDDINGS("rebuild_embeddings", "Memory embedding rebuild", MemoryRun.Type.REBUILD_EMBEDDINGS);

    companion object {
        fun from(value: String): MemoryMaintenanceAction =
            entries.firstOrNull { it.toolName == value.trim().lowercase() || it.name == value.trim().uppercase() }
                ?: throw IllegalArgumentException("Unsupported memory_maintenance action: $value")
    }
}

data class MemoryMaintenanceQueuedResult(
    val runId: MemoryRun.Id,
    val action: MemoryMaintenanceAction,
    val targetKind: String,
    val targetValue: String,
    val namespace: MemoryNamespace,
    val conversationId: Conversation.Id,
    val queueSize: Int,
)

data class MemoryMaintenanceQueueStatus(
    val pendingJobs: Int,
    val activeJob: ActiveMemoryMaintenanceJob?,
    val totalEnqueuedJobs: Long,
    val totalStartedJobs: Long,
    val totalCompletedJobs: Long,
    val totalFatallyFailedJobs: Long,
)

data class ActiveMemoryMaintenanceJob(
    val runId: MemoryRun.Id,
    val action: MemoryMaintenanceAction,
    val targetKind: String,
    val targetValue: String,
    val namespace: MemoryNamespace,
    val conversationId: Conversation.Id,
    val startedAt: Instant,
)

private data class MemoryMaintenanceJob(
    val run: MemoryRun,
    val action: MemoryMaintenanceAction,
    val targetKind: String,
    val targetValue: String,
    val conversationId: Conversation.Id,
    val agent: AgentDefinition,
    val project: Project,
    val namespace: MemoryNamespace,
    val runtimeSystemPrompts: List<String>,
    val runtimeTools: List<AiToolCallback>,
)

private data class MemoryMaintenanceExecutionResult(
    val summary: String,
    val memoryBatch: MemoryUpdateBatch,
    val details: JsonObject,
)

private fun MemoryMaintenanceJob.toActiveJob(startedAt: Instant): ActiveMemoryMaintenanceJob =
    ActiveMemoryMaintenanceJob(
        runId = run.id,
        action = action,
        targetKind = targetKind,
        targetValue = targetValue,
        namespace = namespace,
        conversationId = conversationId,
        startedAt = startedAt,
    )

private fun MemoryMaintenanceExecutionResult.toRunOutput(job: MemoryMaintenanceJob): JsonObject =
    buildJsonObject {
        put("action", job.action.toolName)
        put("target_kind", job.targetKind)
        put("target_value", job.targetValue)
        put("conversation_id", job.conversationId.value)
        put("namespace", job.namespace.value)
        put("counts", memoryBatch.toMaintenanceCountsJson())
        put("details", details)
    }

private fun MemoryUpdateBatch.toMaintenanceCountsJson(): JsonObject =
    buildJsonObject {
        put("predicate_definitions", predicateDefinitions.size)
        put("sources", sources.size)
        put("runs", runs.size)
        put("entities", entities.size)
        put("claims", claims.size)
        put("notes", notes.size)
        put("tasks", tasks.size)
        put("profiles", profiles.size)
        put("episodes", episodes.size)
        put("embeddings", embeddings.size)
    }

private fun String.oneLineForMaintenanceQueueLog(maxChars: Int = 500): String {
    val oneLine = replace(Regex("\\s+"), " ").trim()
    return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}
