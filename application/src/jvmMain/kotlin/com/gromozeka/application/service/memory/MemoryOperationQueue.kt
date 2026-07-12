package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemorySource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
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
        is MemoryOperationRequest.RememberProvidedContent ->
            if (content.documentType == null) MemoryRun.Type.REMEMBER else MemoryRun.Type.DOCUMENT_INGEST
        else -> when (kind) {
            MemoryOperationKind.REMEMBER -> MemoryRun.Type.REMEMBER
            MemoryOperationKind.ENRICH_CONTEXT -> MemoryRun.Type.ENRICH_CONTEXT
            MemoryOperationKind.ANSWER_QUESTION -> MemoryRun.Type.ANSWER_QUESTION
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
    val pendingJobs: Int,
    val activeJob: ActiveMemoryOperation?,
    val totalEnqueuedJobs: Long,
    val totalRecoveredJobs: Long,
    val totalStartedJobs: Long,
    val totalCompletedJobs: Long,
    val totalFatallyFailedJobs: Long,
)

data class ActiveMemoryOperation(
    val runId: MemoryRun.Id,
    val operation: MemoryOperationKind,
    val namespace: MemoryNamespace,
    val startedAt: Instant,
)

@Service
class MemoryOperationQueue(
    @Qualifier("supervisorScope") private val coroutineScope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private val jobs = Channel<MemoryOperationJob>(Channel.UNLIMITED)
    private val scheduledRunIds = ConcurrentHashMap.newKeySet<MemoryRun.Id>()
    private val started = AtomicBoolean(false)
    private val queuedJobs = AtomicInteger(0)
    private val activeJob = AtomicReference<ActiveMemoryOperation?>(null)
    private val totalEnqueuedJobs = AtomicLong(0)
    private val totalRecoveredJobs = AtomicLong(0)
    private val totalStartedJobs = AtomicLong(0)
    private val totalCompletedJobs = AtomicLong(0)
    private val totalFatallyFailedJobs = AtomicLong(0)

    internal fun start(
        recoveredJobs: List<MemoryOperationJob>,
        processor: suspend (MemoryOperationJob) -> Unit,
    ) {
        check(started.compareAndSet(false, true)) { "Memory operation queue is already started." }
        var recoveredCount = 0
        recoveredJobs.forEach { job ->
            if (enqueueInternal(job)) {
                recoveredCount += 1
                totalRecoveredJobs.incrementAndGet()
            }
        }
        if (recoveredCount > 0) {
            log.info { "Recovered memory operations: count=$recoveredCount" }
        }

        coroutineScope.launch {
            for (job in jobs) {
                queuedJobs.updateAndGet { (it - 1).coerceAtLeast(0) }
                val startedAt = Clock.System.now()
                activeJob.set(
                    ActiveMemoryOperation(
                        runId = job.runId,
                        operation = job.operation,
                        namespace = job.namespace,
                        startedAt = startedAt,
                    )
                )
                totalStartedJobs.incrementAndGet()
                try {
                    processor(job)
                    totalCompletedJobs.incrementAndGet()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    totalFatallyFailedJobs.incrementAndGet()
                    log.error(error) {
                        "Memory operation worker failed: run=${job.runId.value} " +
                            "operation=${job.operation.wireName} error=${error.message}"
                    }
                } finally {
                    scheduledRunIds.remove(job.runId)
                    activeJob.set(null)
                }
            }
        }
    }

    internal fun enqueue(job: MemoryOperationJob): Int {
        totalEnqueuedJobs.incrementAndGet()
        if (!enqueueInternal(job)) {
            totalEnqueuedJobs.updateAndGet { (it - 1).coerceAtLeast(0L) }
        }
        return queuedJobs.get()
    }

    fun status(): MemoryOperationQueueStatus =
        MemoryOperationQueueStatus(
            pendingJobs = queuedJobs.get(),
            activeJob = activeJob.get(),
            totalEnqueuedJobs = totalEnqueuedJobs.get(),
            totalRecoveredJobs = totalRecoveredJobs.get(),
            totalStartedJobs = totalStartedJobs.get(),
            totalCompletedJobs = totalCompletedJobs.get(),
            totalFatallyFailedJobs = totalFatallyFailedJobs.get(),
        )

    private fun enqueueInternal(job: MemoryOperationJob): Boolean {
        if (!scheduledRunIds.add(job.runId)) return false
        queuedJobs.incrementAndGet()
        val result = jobs.trySend(job)
        if (result.isFailure) {
            scheduledRunIds.remove(job.runId)
            queuedJobs.updateAndGet { (it - 1).coerceAtLeast(0) }
            result.getOrThrow()
        }
        return true
    }
}

internal const val MEMORY_OPERATION_REQUEST_METADATA_KEY = "operationRequest"
internal const val MEMORY_OPERATION_KIND_METADATA_KEY = "memoryOperation"
