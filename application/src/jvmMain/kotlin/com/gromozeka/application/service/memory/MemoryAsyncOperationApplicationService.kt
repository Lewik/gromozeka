package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class MemoryAsyncOperationApplicationService(
    private val executor: MemoryOperationExecutor,
    private val operationQueue: MemoryOperationQueue,
    private val memoryStore: MemoryStore,
) {
    private val log = KLoggers.logger(this)
    private val operationJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        classDiscriminator = "requestType"
    }

    @EventListener(ApplicationReadyEvent::class)
    fun start() = runBlocking {
        operationQueue.start(
            recoveredJobs = recoverJobs(),
            processor = ::process,
        )
    }

    suspend fun rememberMessage(
        conversationIdValue: String,
        targetMessageId: String? = null,
        forceWrite: Boolean? = null,
        confirmedPreflightRunId: String? = null,
        namespaceValue: String? = null,
    ): String =
        schedule(MemoryOperationKind.REMEMBER) {
            executor.prepareRememberMessage(
                conversationIdValue = conversationIdValue,
                targetMessageId = targetMessageId,
                forceWrite = forceWrite,
                confirmedPreflightRunId = confirmedPreflightRunId,
                namespaceValue = namespaceValue,
            )
        }

    suspend fun rememberProvidedContent(
        conversationIdValue: String?,
        text: String? = null,
        filePath: String? = null,
        rawUrl: String? = null,
        documentType: String? = null,
        title: String? = null,
        sourceRef: String? = null,
        forceWrite: Boolean? = null,
        confirmedPreflightRunId: String? = null,
        mode: String? = null,
        namespaceValue: String? = null,
        writeSurface: MemoryWriteSurface = MemoryWriteSurface.CHAT_TOOL,
    ): String =
        schedule(MemoryOperationKind.REMEMBER) {
            executor.prepareRememberProvidedContent(
                conversationIdValue = conversationIdValue,
                text = text,
                filePath = filePath,
                rawUrl = rawUrl,
                documentType = documentType,
                title = title,
                sourceRef = sourceRef,
                forceWrite = forceWrite,
                confirmedPreflightRunId = confirmedPreflightRunId,
                mode = mode,
                namespaceValue = namespaceValue,
                writeSurface = writeSurface,
            )
        }

    suspend fun enrichMessage(
        conversationIdValue: String,
        targetMessageId: String? = null,
        namespaceValue: String? = null,
    ): String =
        schedule(MemoryOperationKind.ENRICH_CONTEXT) {
            executor.prepareEnrichMessage(
                conversationIdValue = conversationIdValue,
                targetMessageId = targetMessageId,
                namespaceValue = namespaceValue,
            )
        }

    suspend fun enrichProvidedContext(
        conversationIdValue: String?,
        contextText: String,
        mode: String? = null,
        namespaceValue: String? = null,
    ): String =
        schedule(MemoryOperationKind.ENRICH_CONTEXT) {
            executor.prepareEnrichProvidedContext(
                conversationIdValue = conversationIdValue,
                contextText = contextText,
                mode = mode,
                namespaceValue = namespaceValue,
            )
        }

    suspend fun answerMessage(
        conversationIdValue: String,
        targetMessageId: String? = null,
        namespaceValue: String? = null,
    ): String =
        schedule(MemoryOperationKind.ANSWER_QUESTION) {
            executor.prepareAnswerMessage(
                conversationIdValue = conversationIdValue,
                targetMessageId = targetMessageId,
                namespaceValue = namespaceValue,
            )
        }

    suspend fun answerProvidedQuestion(
        conversationIdValue: String?,
        questionText: String,
        mode: String? = null,
        namespaceValue: String? = null,
    ): String =
        schedule(MemoryOperationKind.ANSWER_QUESTION) {
            executor.prepareAnswerProvidedQuestion(
                conversationIdValue = conversationIdValue,
                questionText = questionText,
                mode = mode,
                namespaceValue = namespaceValue,
            )
        }

    private suspend fun schedule(
        operation: MemoryOperationKind,
        prepare: suspend () -> PreparedMemoryOperation,
    ): String = try {
        enqueue(prepare())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        log.warn(error) {
            "Memory operation was rejected before queueing: operation=${operation.wireName} error=${error.message}"
        }
        MemoryToolResultRenderer.failureJsonString(
            error.message ?: "Memory ${operation.wireName} could not be queued."
        )
    }

    private suspend fun enqueue(prepared: PreparedMemoryOperation): String {
        prepared.request.validate()
        val now = Clock.System.now()
        val run = MemoryRun(
            id = MemoryRun.Id("memory-operation:${prepared.request.kind.wireName}:run:${uuid7()}"),
            namespace = prepared.namespace,
            runType = prepared.runType,
            triggerMode = MemoryRun.TriggerMode.MANUAL,
            summary = prepared.summary,
            sourceIds = prepared.sourceIds,
            progress = prepared.progress,
            inputHash = prepared.inputHash,
            output = prepared.initialOutput,
            metadata = buildJsonObject {
                put(MEMORY_OPERATION_KIND_METADATA_KEY, prepared.request.kind.wireName)
                put(
                    MEMORY_OPERATION_REQUEST_METADATA_KEY,
                    operationJson.encodeToJsonElement(MemoryOperationRequest.serializer(), prepared.request),
                )
            },
            status = MemoryRun.Status.QUEUED,
            createdAt = now,
        )
        memoryStore.apply(
            MemoryUpdateBatch(
                sources = prepared.sources,
                runs = listOf(run),
            )
        )

        return runCatching {
            val queueSize = operationQueue.enqueue(
                MemoryOperationJob(
                    runId = run.id,
                    operation = prepared.request.kind,
                    namespace = prepared.namespace,
                )
            )
            log.info {
                "Memory operation queued: run=${run.id.value} operation=${prepared.request.kind.wireName} " +
                    "namespace=${prepared.namespace.value} queueSize=$queueSize"
            }
            MemoryToolResultRenderer.operationQueuedResultJsonString(
                MemoryOperationQueuedResult(
                    runId = run.id,
                    operation = prepared.request.kind,
                    namespace = prepared.namespace,
                    queueSize = queueSize,
                )
            )
        }.getOrElse { error ->
            val completedAt = Clock.System.now()
            memoryStore.apply(
                MemoryUpdateBatch(
                    runs = listOf(
                        run.failed(
                            summary = "Memory operation could not be queued",
                            reason = error.message ?: error::class.simpleName.orEmpty(),
                            completedAt = completedAt,
                        )
                    )
                )
            )
            MemoryToolResultRenderer.failureJsonString(error.message ?: "Memory operation could not be queued.")
        }
    }

    private suspend fun recoverJobs(): List<MemoryOperationJob> {
        val unfinishedRuns = memoryStore.findRunsByStatuses(
            statuses = setOf(MemoryRun.Status.QUEUED, MemoryRun.Status.RUNNING),
            runTypes = setOf(
                MemoryRun.Type.REMEMBER,
                MemoryRun.Type.DOCUMENT_INGEST,
                MemoryRun.Type.ENRICH_CONTEXT,
                MemoryRun.Type.ANSWER_QUESTION,
            ),
        )
        return unfinishedRuns.mapNotNull { run ->
            if (run.status == MemoryRun.Status.RUNNING) {
                failUnrecoverableRun(
                    run = run,
                    summary = "Memory operation was interrupted by server shutdown",
                    reason = "The server stopped while this operation was running; it was not retried to avoid duplicate memory writes.",
                )
                log.warn { "Failed interrupted memory operation without retry: run=${run.id.value} type=${run.runType}" }
                return@mapNotNull null
            }
            val encodedRequest = run.metadata[MEMORY_OPERATION_REQUEST_METADATA_KEY]
            if (encodedRequest == null) {
                failUnrecoverableRun(
                    run = run,
                    summary = "Memory operation cannot be resumed",
                    reason = "Persisted memory operation request is missing.",
                )
                log.warn { "Failed queued memory operation without a request: run=${run.id.value} type=${run.runType}" }
                return@mapNotNull null
            }
            val request = try {
                operationJson.decodeFromJsonElement<MemoryOperationRequest>(encodedRequest)
            } catch (error: Throwable) {
                failUnrecoverableRun(
                    run = run,
                    summary = "Memory operation cannot be resumed",
                    reason = error.message ?: "Persisted memory operation request is invalid.",
                )
                log.warn(error) { "Failed queued memory operation with an invalid request: run=${run.id.value}" }
                return@mapNotNull null
            }
            val requestValidationError = runCatching { request.validate() }
                .exceptionOrNull()
                ?.message
            val validationError = when {
                requestValidationError != null -> requestValidationError
                request.namespace != run.namespace ->
                    "Persisted operation namespace ${request.namespace.value} does not match run namespace ${run.namespace.value}."
                request.runType != run.runType ->
                    "Persisted operation type ${request.runType} does not match run type ${run.runType}."
                else -> null
            }
            if (validationError != null) {
                failUnrecoverableRun(
                    run = run,
                    summary = "Memory operation cannot be resumed",
                    reason = validationError,
                )
                log.warn { "Failed inconsistent queued memory operation: run=${run.id.value} reason=$validationError" }
                return@mapNotNull null
            }
            MemoryOperationJob(run.id, request.kind, run.namespace)
        }
    }

    private suspend fun failUnrecoverableRun(
        run: MemoryRun,
        summary: String,
        reason: String,
    ) {
        val completedAt = Clock.System.now()
        memoryStore.apply(
            MemoryUpdateBatch(
                runs = listOf(
                    run.failed(
                        summary = summary,
                        reason = reason,
                        completedAt = completedAt,
                    )
                )
            )
        )
    }

    private suspend fun process(job: MemoryOperationJob) {
        val storedRun = memoryStore.findRunById(job.runId)
            ?: throw IllegalStateException("Queued memory operation run not found: ${job.runId.value}")
        if (storedRun.status.isTerminal()) return
        var currentRun = storedRun
        try {
            val encodedRequest = storedRun.metadata[MEMORY_OPERATION_REQUEST_METADATA_KEY]
                ?: throw IllegalStateException("Memory operation request is missing: ${job.runId.value}")
            val request = operationJson.decodeFromJsonElement<MemoryOperationRequest>(encodedRequest)
            request.validate()
            require(request.kind == job.operation) {
                "Memory operation kind mismatch: queued=${job.operation.wireName} persisted=${request.kind.wireName}"
            }
            require(request.namespace == storedRun.namespace) {
                "Memory operation namespace mismatch: request=${request.namespace.value} run=${storedRun.namespace.value}"
            }
            require(request.runType == storedRun.runType) {
                "Memory operation type mismatch: request=${request.runType} run=${storedRun.runType}"
            }

            val startedAt = Clock.System.now()
            currentRun = storedRun.copy(
                status = MemoryRun.Status.RUNNING,
                summary = "${request.kind.displayName()} running",
                startedAt = startedAt,
                completedAt = null,
                errorText = null,
            )
            memoryStore.apply(MemoryUpdateBatch(runs = listOf(currentRun)))

            val execution = executor.execute(currentRun, request)
            val completedAt = Clock.System.now()
            val completedRun = currentRun.complete(execution, startedAt, completedAt)
            memoryStore.apply(MemoryUpdateBatch(runs = listOf(completedRun)))
            log.info {
                "Memory operation completed: run=${job.runId.value} operation=${request.kind.wireName} " +
                    "status=${execution.status} latencyMs=${completedRun.latencyMs}"
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val completedAt = Clock.System.now()
            val failedBaseRun = memoryStore.findRunById(job.runId) ?: currentRun
            memoryStore.apply(
                MemoryUpdateBatch(
                    runs = listOf(
                        failedBaseRun.failed(
                            summary = "${job.operation.displayName()} failed",
                            reason = error.message ?: "Memory operation failed.",
                            completedAt = completedAt,
                        )
                    )
                )
            )
            throw error
        }
    }

    private fun MemoryRun.Status.isTerminal(): Boolean =
        this == MemoryRun.Status.NEEDS_INPUT ||
            this == MemoryRun.Status.SUCCESS ||
            this == MemoryRun.Status.FAILED ||
            this == MemoryRun.Status.PARTIAL ||
            this == MemoryRun.Status.CANCELLED

    private fun MemoryRun.failed(
        summary: String,
        reason: String,
        completedAt: kotlinx.datetime.Instant,
    ): MemoryRun {
        val currentProgress = progress ?: MemoryRun.Progress(totalUnits = 1)
        return copy(
            status = MemoryRun.Status.FAILED,
            summary = summary,
            progress = currentProgress.copy(
                totalUnits = maxOf(currentProgress.totalUnits, 1),
                failedUnits = maxOf(currentProgress.failedUnits, 1),
            ),
            errorText = reason,
            output = operationJson.parseToJsonElement(MemoryToolResultRenderer.failureJsonString(reason)),
            latencyMs = startedAt?.let { started ->
                completedAt.toEpochMilliseconds() - started.toEpochMilliseconds()
            },
            completedAt = completedAt,
        )
    }

    private fun MemoryOperationKind.displayName(): String =
        when (this) {
            MemoryOperationKind.REMEMBER -> "Memory remember"
            MemoryOperationKind.ENRICH_CONTEXT -> "Memory context enrichment"
            MemoryOperationKind.ANSWER_QUESTION -> "Memory question answering"
        }
}
