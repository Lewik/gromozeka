package com.gromozeka.application.service

import com.gromozeka.application.service.memory.LlmMemoryReadPlanner
import com.gromozeka.application.service.memory.LlmMemoryReadSelector
import com.gromozeka.application.service.memory.LlmMemoryEntityMaintenancePlanner
import com.gromozeka.application.service.memory.LlmMemoryNoteConsolidator
import com.gromozeka.application.service.memory.LlmMemoryRepairPlanner
import com.gromozeka.application.service.memory.MemoryEntityMaintenancePipeline
import com.gromozeka.application.service.memory.MemoryEntityMaintenancePipelineResult
import com.gromozeka.application.service.memory.MemoryContextWindowPolicy
import com.gromozeka.application.service.memory.MemoryMaintenanceTraceEvent
import com.gromozeka.application.service.memory.MemoryMaintenanceTraceSink
import com.gromozeka.application.service.memory.MemoryThreadContextCompactor
import com.gromozeka.application.service.memory.MemoryNoteConsolidationPipeline
import com.gromozeka.application.service.memory.MemoryNoteConsolidationPipelineResult
import com.gromozeka.application.service.memory.defaultMemoryNamespace
import com.gromozeka.application.service.memory.MemoryRepairPipeline
import com.gromozeka.application.service.memory.MemoryRepairPipelineResult
import com.gromozeka.application.service.memory.MemoryRetentionPipeline
import com.gromozeka.application.service.memory.MemoryRetentionPipelineResult
import com.gromozeka.application.service.memory.MemoryEmbeddingIndexer
import com.gromozeka.application.service.memory.MemoryReadTraceEvent
import com.gromozeka.application.service.memory.MemoryReadTraceSink
import com.gromozeka.application.service.memory.PolicyMemoryRetentionPlanner
import com.gromozeka.application.service.memory.ProjectionMemoryProfileUpdater
import com.gromozeka.application.service.memory.RuntimeMemoryReadPipeline
import com.gromozeka.application.service.memory.UuidMemoryIdFactory
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.memory.MemoryMaintenanceRequest
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryReadResult
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryReadRequest
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryThreadContext
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.stereotype.Service

@Service
class MemoryApplicationService(
    private val aiRuntimeProvider: AiRuntimeProvider,
    private val settingsProvider: SettingsProvider,
    private val store: MemoryStore,
    private val readTraceSinks: List<MemoryReadTraceSink>,
    private val maintenanceTraceSinks: List<MemoryMaintenanceTraceSink>,
    private val embeddingIndexer: MemoryEmbeddingIndexer,
) {
    private val log = KLoggers.logger(this)

    suspend fun ingestCurrentThread(
        conversationId: Conversation.Id,
        agent: AgentDefinition? = null,
    ) {
        log.info { "Legacy memory ingest disabled: conversation=${conversationId.value} agent=${agent?.name}" }
    }

    suspend fun buildRuntimeMemoryPrompt(
        conversationId: Conversation.Id,
        threadId: Conversation.Thread.Id,
        targetMessage: Conversation.Message,
        threadMessages: List<Conversation.Message>,
        agent: AgentDefinition,
        project: Project,
        runtimeSystemPrompts: List<String>,
        runtimeTools: List<AiToolCallback>,
    ): String? {
        return buildRuntimeMemoryReadResult(
            conversationId = conversationId,
            threadId = threadId,
            targetMessage = targetMessage,
            threadMessages = threadMessages,
            agent = agent,
            project = project,
            runtimeSystemPrompts = runtimeSystemPrompts,
            runtimeTools = runtimeTools,
        ).runtimePrompt
    }

    suspend fun buildRuntimeMemoryReadResult(
        conversationId: Conversation.Id,
        threadId: Conversation.Thread.Id,
        targetMessage: Conversation.Message,
        threadMessages: List<Conversation.Message>,
        agent: AgentDefinition,
        project: Project,
        runtimeSystemPrompts: List<String>,
        runtimeTools: List<AiToolCallback>,
        namespaceOverride: MemoryNamespace? = null,
    ): MemoryReadResult {
        val memoryContextMessages = threadMessages.filterNot { it.isSyntheticMemoryRuntimeMessage() }
        val targetIndex = memoryContextMessages.indexOfFirst { it.id == targetMessage.id }
        val contextMessages = if (targetIndex >= 0) {
            memoryContextMessages.take(targetIndex + 1)
        } else {
            log.warn {
                "Memory read context target missing in thread messages: conversation=${conversationId.value} " +
                    "thread=${threadId.value} target=${targetMessage.id.value}; appending target"
            }
            memoryContextMessages + targetMessage
        }
        val namespace = namespaceOverride
            ?: settingsProvider.userProfile.memorySettings.defaultMemoryNamespace()
            ?: project.defaultMemoryNamespace()
        val runtimes = MemoryServiceStageRuntimes(project)
        val threadContext = MemoryThreadContext(
            conversationId = conversationId,
            threadId = threadId,
            targetMessageId = targetMessage.id,
            messages = contextMessages,
        )
        val focusedThreadContext = MemoryThreadContextCompactor(
            runtime = runtimes.runtimeFor(AiRuntimeAssignment.Purpose.MEMORY_READ_CONTEXT_COMPACTOR),
            preCompactThresholdTokens = MemoryContextWindowPolicy.readPreCompactThresholdTokens(settingsProvider.userProfile.aiSettings),
        ).compactIfNeeded(
            context = threadContext,
            targetSourceLabel = "chat:${targetMessage.id.value}",
            logContext = "conversation=${conversationId.value} thread=${threadId.value} purpose=memory_read",
        )
        val pipeline = RuntimeMemoryReadPipeline(
            store = store,
            planner = LlmMemoryReadPlanner(
                runtime = runtimes.runtimeFor(AiRuntimeAssignment.Purpose.MEMORY_READ_PLANNER),
                timezone = TimeZone.currentSystemDefault().id,
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            selector = LlmMemoryReadSelector(
                runtime = runtimes.runtimeFor(AiRuntimeAssignment.Purpose.MEMORY_READ_SELECTOR),
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            embeddingIndexer = embeddingIndexer,
        )
        val result = pipeline.read(
            MemoryReadRequest(
                namespace = namespace,
                threadContext = focusedThreadContext,
            )
        )

        log.info {
            "Memory runtime recall completed: conversation=${conversationId.value} thread=${threadId.value} " +
                "target=${targetMessage.id.value} need=${result.plan.needMemory} mode=${result.plan.answerMode.name} " +
                "hits=${result.retrievedHits.size} promptChars=${result.runtimePrompt?.length ?: 0}"
        }
        readTraceSinks.forEach { sink ->
            runCatching {
                sink.onMemoryRead(
                    MemoryReadTraceEvent(
                        namespace = namespace,
                        conversationId = conversationId,
                        threadId = threadId,
                        targetMessageId = targetMessage.id,
                        result = result,
                    )
                )
            }.onFailure { error ->
                log.warn(error) {
                    "Memory read trace sink failed: conversation=${conversationId.value} " +
                        "target=${targetMessage.id.value} sink=${sink::class.simpleName} error=${error.message}"
                }
            }
        }

        return result
    }

    private inner class MemoryServiceStageRuntimes(
        private val project: Project,
    ) {
        private val runtimes = mutableMapOf<AiRuntimeAssignment.Purpose, AiRuntime>()

        fun runtimeFor(purpose: AiRuntimeAssignment.Purpose): AiRuntime =
            runtimes.getOrPut(purpose) {
                aiRuntimeProvider.getRuntime(
                    selection = settingsProvider.runtimeSelectionFor(purpose),
                    projectPath = project.path,
                )
            }
    }

    private fun Conversation.Message.isSyntheticMemoryRuntimeMessage(): Boolean =
        providerMetadata["syntheticKind"]?.jsonPrimitive?.contentOrNull == "memory"

    suspend fun runNoteConsolidation(
        conversationId: Conversation.Id,
        agent: AgentDefinition,
        project: Project,
        runtimeSystemPrompts: List<String>,
        runtimeTools: List<AiToolCallback>,
        namespace: MemoryNamespace = project.defaultMemoryNamespace(),
    ): MemoryNoteConsolidationPipelineResult {
        val runtimes = MemoryServiceStageRuntimes(project)
        val pipeline = MemoryNoteConsolidationPipeline(
            store = store,
            consolidator = LlmMemoryNoteConsolidator(
                runtime = runtimes.runtimeFor(AiRuntimeAssignment.Purpose.MEMORY_MAINTENANCE_NOTE_CONSOLIDATOR),
                timezone = TimeZone.currentSystemDefault().id,
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            idFactory = UuidMemoryIdFactory("note-consolidation"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            embeddingIndexer = embeddingIndexer,
        )
        val result = pipeline.run(
            MemoryMaintenanceRequest(
                namespace = namespace,
                conversationId = conversationId,
                triggerMode = MemoryRun.TriggerMode.MANUAL,
            )
        )

        log.info {
            "Memory note consolidation service completed: conversation=${conversationId.value} namespace=${namespace.value} " +
                "selectedNotes=${result.selectedNotes.size} relatedHits=${result.relatedHits.size} " +
                "claims=${result.memoryBatch.claims.size} notes=${result.memoryBatch.notes.size} tasks=${result.memoryBatch.tasks.size} profiles=${result.memoryBatch.profiles.size}"
        }
        emitMaintenanceTrace(
            MemoryMaintenanceTraceEvent(
                namespace = namespace,
                conversationId = conversationId,
                stage = MemoryMaintenanceTraceEvent.Stage.NOTE_CONSOLIDATION,
                payload = MemoryMaintenanceTraceEvent.Payload.NoteConsolidation(result),
            )
        )
        return result
    }

    suspend fun runMemoryRepair(
        conversationId: Conversation.Id,
        agent: AgentDefinition,
        project: Project,
        runtimeSystemPrompts: List<String>,
        runtimeTools: List<AiToolCallback>,
        namespace: MemoryNamespace = project.defaultMemoryNamespace(),
    ): MemoryRepairPipelineResult {
        val runtimes = MemoryServiceStageRuntimes(project)
        val pipeline = MemoryRepairPipeline(
            store = store,
            planner = LlmMemoryRepairPlanner(
                runtime = runtimes.runtimeFor(AiRuntimeAssignment.Purpose.MEMORY_MAINTENANCE_REPAIR_PLANNER),
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            idFactory = UuidMemoryIdFactory("memory-repair"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            embeddingIndexer = embeddingIndexer,
        )
        val result = pipeline.run(
            MemoryMaintenanceRequest(
                namespace = namespace,
                conversationId = conversationId,
                triggerMode = MemoryRun.TriggerMode.MANUAL,
            )
        )

        log.info {
            "Memory repair service completed: conversation=${conversationId.value} namespace=${namespace.value} " +
                "suspiciousHits=${result.suspiciousHits.size} actions=${result.repairPlan.repairActions.size} " +
                "claims=${result.memoryBatch.claims.size} notes=${result.memoryBatch.notes.size} tasks=${result.memoryBatch.tasks.size} profiles=${result.memoryBatch.profiles.size}"
        }
        emitMaintenanceTrace(
            MemoryMaintenanceTraceEvent(
                namespace = namespace,
                conversationId = conversationId,
                stage = MemoryMaintenanceTraceEvent.Stage.MEMORY_REPAIR,
                payload = MemoryMaintenanceTraceEvent.Payload.MemoryRepair(result),
            )
        )
        return result
    }

    suspend fun runEntityMaintenance(
        conversationId: Conversation.Id,
        agent: AgentDefinition,
        project: Project,
        runtimeSystemPrompts: List<String>,
        runtimeTools: List<AiToolCallback>,
        namespace: MemoryNamespace = project.defaultMemoryNamespace(),
    ): MemoryEntityMaintenancePipelineResult {
        val runtimes = MemoryServiceStageRuntimes(project)
        val pipeline = MemoryEntityMaintenancePipeline(
            store = store,
            planner = LlmMemoryEntityMaintenancePlanner(
                runtime = runtimes.runtimeFor(AiRuntimeAssignment.Purpose.MEMORY_MAINTENANCE_ENTITY_PLANNER),
                runtimeSystemPrompts = runtimeSystemPrompts,
                runtimeTools = runtimeTools,
            ),
            idFactory = UuidMemoryIdFactory("entity-maintenance"),
            profileUpdater = ProjectionMemoryProfileUpdater(store),
            embeddingIndexer = embeddingIndexer,
        )
        val result = pipeline.run(
            MemoryMaintenanceRequest(
                namespace = namespace,
                conversationId = conversationId,
                triggerMode = MemoryRun.TriggerMode.MANUAL,
            )
        )

        log.info {
            "Memory entity maintenance service completed: conversation=${conversationId.value} namespace=${namespace.value} " +
                "groups=${result.candidateGroups.size} actions=${result.maintenancePlan.actions.size} " +
                "entities=${result.memoryBatch.entities.size} claims=${result.memoryBatch.claims.size} notes=${result.memoryBatch.notes.size} " +
                "tasks=${result.memoryBatch.tasks.size} profiles=${result.memoryBatch.profiles.size} episodes=${result.memoryBatch.episodes.size}"
        }
        emitMaintenanceTrace(
            MemoryMaintenanceTraceEvent(
                namespace = namespace,
                conversationId = conversationId,
                stage = MemoryMaintenanceTraceEvent.Stage.ENTITY_MAINTENANCE,
                payload = MemoryMaintenanceTraceEvent.Payload.EntityMaintenance(result),
            )
        )
        return result
    }

    suspend fun runRetention(
        conversationId: Conversation.Id,
        project: Project,
        namespace: MemoryNamespace = project.defaultMemoryNamespace(),
    ): MemoryRetentionPipelineResult {
        val pipeline = MemoryRetentionPipeline(
            store = store,
            planner = PolicyMemoryRetentionPlanner(),
            idFactory = UuidMemoryIdFactory("memory-retention"),
        )
        val result = pipeline.run(
            MemoryMaintenanceRequest(
                namespace = namespace,
                conversationId = conversationId,
                triggerMode = MemoryRun.TriggerMode.MANUAL,
            )
        )

        log.info {
            "Memory retention service completed: conversation=${conversationId.value} namespace=${namespace.value} " +
                "candidates=${result.candidates.size} actions=${result.retentionPlan.retentionActions.size} " +
                "claims=${result.memoryBatch.claims.size} notes=${result.memoryBatch.notes.size} tasks=${result.memoryBatch.tasks.size}"
        }
        emitMaintenanceTrace(
            MemoryMaintenanceTraceEvent(
                namespace = namespace,
                conversationId = conversationId,
                stage = MemoryMaintenanceTraceEvent.Stage.RETENTION,
                payload = MemoryMaintenanceTraceEvent.Payload.Retention(result),
            )
        )
        return result
    }

    private fun emitMaintenanceTrace(event: MemoryMaintenanceTraceEvent) {
        maintenanceTraceSinks.forEach { sink ->
            runCatching {
                sink.onMemoryMaintenance(event)
            }.onFailure { error ->
                log.warn(error) {
                    "Memory maintenance trace sink failed: conversation=${event.conversationId.value} " +
                        "stage=${event.stage.name} sink=${sink::class.simpleName} error=${error.message}"
                }
            }
        }
    }
}
