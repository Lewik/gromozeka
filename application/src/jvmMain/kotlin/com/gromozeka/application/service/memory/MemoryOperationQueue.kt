package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

@Serializable
enum class MemoryOperationKind(val wireName: String) {
    REMEMBER("remember"),
    ENRICH_CONTEXT("enrich_context"),
    ANSWER_QUESTION("answer_question"),
    MAINTENANCE("maintenance"),
}

@Serializable
enum class MemoryMaintenanceTargetKind(val wireName: String) {
    CONVERSATION_ID("conversation_id"),
    PROJECT_ID("project_id");

    companion object {
        fun from(value: String): MemoryMaintenanceTargetKind =
            entries.firstOrNull { it.wireName == value.trim().lowercase() }
                ?: throw IllegalArgumentException("Unsupported memory maintenance target kind: $value")
    }
}

@Serializable
sealed interface MemoryOperationRequest {
    val kind: MemoryOperationKind
    val namespace: MemoryNamespace
    val conversationId: Conversation.Id?
    val threadId: Conversation.Thread.Id?

    @Serializable
    @SerialName("remember_message")
    data class RememberMessage(
        override val namespace: MemoryNamespace,
        override val conversationId: Conversation.Id,
        override val threadId: Conversation.Thread.Id,
        val targetMessageId: Conversation.Message.Id,
        val forceWrite: Boolean?,
        val confirmedPreflightRunId: MemoryRun.Id?,
    ) : MemoryOperationRequest {
        override val kind: MemoryOperationKind = MemoryOperationKind.REMEMBER
    }

    @Serializable
    @SerialName("remember_provided_content")
    data class RememberProvidedContent(
        override val namespace: MemoryNamespace,
        override val conversationId: Conversation.Id?,
        override val threadId: Conversation.Thread.Id?,
        val content: MemoryRememberContentRequest,
        val forceWrite: Boolean?,
        val confirmedPreflightRunId: MemoryRun.Id?,
        val mode: String?,
        val writeSurface: MemoryWriteSurface,
    ) : MemoryOperationRequest {
        override val kind: MemoryOperationKind = MemoryOperationKind.REMEMBER
    }

    @Serializable
    @SerialName("enrich_message")
    data class EnrichMessage(
        override val namespace: MemoryNamespace,
        override val conversationId: Conversation.Id,
        override val threadId: Conversation.Thread.Id,
        val targetMessageId: Conversation.Message.Id,
    ) : MemoryOperationRequest {
        override val kind: MemoryOperationKind = MemoryOperationKind.ENRICH_CONTEXT
    }

    @Serializable
    @SerialName("enrich_provided_context")
    data class EnrichProvidedContext(
        override val namespace: MemoryNamespace,
        override val conversationId: Conversation.Id?,
        override val threadId: Conversation.Thread.Id?,
        val context: String,
        val mode: String?,
    ) : MemoryOperationRequest {
        override val kind: MemoryOperationKind = MemoryOperationKind.ENRICH_CONTEXT
    }

    @Serializable
    @SerialName("answer_message")
    data class AnswerMessage(
        override val namespace: MemoryNamespace,
        override val conversationId: Conversation.Id,
        override val threadId: Conversation.Thread.Id,
        val targetMessageId: Conversation.Message.Id,
    ) : MemoryOperationRequest {
        override val kind: MemoryOperationKind = MemoryOperationKind.ANSWER_QUESTION
    }

    @Serializable
    @SerialName("answer_provided_question")
    data class AnswerProvidedQuestion(
        override val namespace: MemoryNamespace,
        override val conversationId: Conversation.Id?,
        override val threadId: Conversation.Thread.Id?,
        val question: String,
        val mode: String?,
    ) : MemoryOperationRequest {
        override val kind: MemoryOperationKind = MemoryOperationKind.ANSWER_QUESTION
    }

    @Serializable
    @SerialName("maintenance")
    data class Maintenance(
        override val namespace: MemoryNamespace,
        val action: MemoryMaintenanceAction,
        val targetKind: MemoryMaintenanceTargetKind,
        val targetValue: String,
        val executionConversationId: Conversation.Id,
        val embeddingRebuildMode: MemoryEmbeddingRebuildMode,
    ) : MemoryOperationRequest {
        override val kind: MemoryOperationKind = MemoryOperationKind.MAINTENANCE
        override val conversationId: Conversation.Id? = null
        override val threadId: Conversation.Thread.Id? = null

        init {
            require(targetValue.isNotBlank()) { "Memory maintenance target value must not be blank" }
        }
    }
}

data class PreparedMemoryOperation(
    val request: MemoryOperationRequest,
    val summary: String,
    val sources: List<MemorySource> = emptyList(),
    val sourceIds: List<MemorySource.Id> = sources.map { it.id },
    val progress: MemoryRun.Progress = MemoryRun.Progress(totalUnits = 1),
    val inputHash: String? = null,
    val initialOutput: JsonElement? = null,
) {
    val namespace: MemoryNamespace get() = request.namespace
    val runType: MemoryRun.Type get() = request.runType
}

internal val MemoryOperationRequest.runType: MemoryRun.Type
    get() = when (this) {
        is MemoryOperationRequest.Maintenance -> action.runType
        is MemoryOperationRequest.RememberProvidedContent ->
            if (content.documentType == null) MemoryRun.Type.REMEMBER else MemoryRun.Type.DOCUMENT_INGEST
        else -> when (kind) {
            MemoryOperationKind.REMEMBER -> MemoryRun.Type.REMEMBER
            MemoryOperationKind.ENRICH_CONTEXT -> MemoryRun.Type.ENRICH_CONTEXT
            MemoryOperationKind.ANSWER_QUESTION -> MemoryRun.Type.ANSWER_QUESTION
            MemoryOperationKind.MAINTENANCE -> error("Maintenance run type must come from its action")
        }
    }

internal fun MemoryOperationRequest.validate() {
    require((conversationId == null) == (threadId == null)) {
        "Memory operation conversationId and threadId must either both be present or both be absent."
    }
}

internal data class MemoryOperationExecution(
    val status: MemoryRun.Status,
    val summary: String,
    val output: JsonElement,
    val inputHash: String? = null,
    val llmCalls: List<MemoryRun.LlmCallTiming> = emptyList(),
    val sourceIds: List<MemorySource.Id> = emptyList(),
    val childRunIds: List<MemoryRun.Id> = emptyList(),
    val progress: MemoryRun.Progress = MemoryRun.Progress(totalUnits = 1, completedUnits = 1),
    val errorText: String? = null,
)

internal data class MemoryOperationJob(
    val runId: MemoryRun.Id,
    val operation: MemoryOperationKind,
    val namespace: MemoryNamespace,
)

data class MemoryOperationQueuedResult(
    val runId: MemoryRun.Id,
    val operation: MemoryOperationKind,
    val namespace: MemoryNamespace,
    val queueSize: Int,
)

data class MemoryOperationQueueStatus(
    val queuedJobs: Int,
    val activeJobs: List<ActiveMemoryOperation>,
    val onlineWorkers: List<ConversationRuntimeWorkerRegistration>,
)

data class ActiveMemoryOperation(
    val runId: MemoryRun.Id,
    val runType: MemoryRun.Type,
    val operation: MemoryOperationKind?,
    val namespace: MemoryNamespace,
    val startedAt: Instant?,
    val executionLease: MemoryRun.ExecutionLease?,
    val leaseExpired: Boolean,
)

@Service
class MemoryOperationQueue(
    @Qualifier("applicationScope") private val coroutineScope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private val jobs = Channel<MemoryOperationJob>(Channel.UNLIMITED)
    private val scheduledRunIds = ConcurrentHashMap.newKeySet<MemoryRun.Id>()
    private val started = AtomicBoolean(false)

    internal fun start(
        jobSource: suspend () -> List<MemoryOperationJob>,
        processor: suspend (MemoryOperationJob) -> Unit,
    ) {
        check(started.compareAndSet(false, true)) { "Memory operation queue is already started." }

        coroutineScope.launch {
            for (job in jobs) {
                try {
                    processor(job)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log.error(error) {
                        "Memory operation worker failed: run=${job.runId.value} " +
                            "operation=${job.operation.wireName} error=${error.message}"
                    }
                } finally {
                    scheduledRunIds.remove(job.runId)
                }
            }
        }

        coroutineScope.launch {
            while (currentCoroutineContext().isActive) {
                try {
                    jobSource().forEach { job ->
                        enqueueInternal(job)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log.error(error) { "Memory operation durable scan failed: ${error.message}" }
                }
                delay(DURABLE_SCAN_INTERVAL_MILLIS)
            }
        }
    }

    private fun enqueueInternal(job: MemoryOperationJob): Boolean {
        if (!scheduledRunIds.add(job.runId)) return false
        val result = jobs.trySend(job)
        if (result.isFailure) {
            scheduledRunIds.remove(job.runId)
            result.getOrThrow()
        }
        return true
    }

    private companion object {
        const val DURABLE_SCAN_INTERVAL_MILLIS = 500L
    }
}

internal const val MEMORY_OPERATION_REQUEST_METADATA_KEY = "operationRequest"
internal const val MEMORY_OPERATION_KIND_METADATA_KEY = "memoryOperation"
internal val MEMORY_OPERATION_RUN_TYPES = setOf(
    MemoryRun.Type.REMEMBER,
    MemoryRun.Type.DOCUMENT_INGEST,
    MemoryRun.Type.ENRICH_CONTEXT,
    MemoryRun.Type.ANSWER_QUESTION,
    MemoryRun.Type.CONSOLIDATE_NOTES,
    MemoryRun.Type.REPAIR_MEMORY,
    MemoryRun.Type.MAINTAIN_ENTITIES,
    MemoryRun.Type.APPLY_RETENTION,
    MemoryRun.Type.REBUILD_EMBEDDINGS,
)
