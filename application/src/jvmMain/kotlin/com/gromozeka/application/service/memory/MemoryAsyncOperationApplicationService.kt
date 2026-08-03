package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.model.WorkspacePathReference
import com.gromozeka.domain.service.WorkspacePathAccessContext
import com.gromozeka.domain.service.MemoryRunLifecycleEvent
import com.gromozeka.domain.service.MemoryRunLifecycleEventPublisher
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service

@Service
class MemoryAsyncOperationApplicationService(
    private val preparer: MemoryOperationPreparer,
    private val memoryStore: MemoryStore,
    private val operationQueue: MemoryOperationQueue,
    private val lifecycleEventPublisher: MemoryRunLifecycleEventPublisher,
) {
    private val log = KLoggers.logger(this)
    private val operationJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        classDiscriminator = "requestType"
    }

    suspend fun rememberMessage(
        conversationIdValue: String,
        namespace: MemoryNamespace,
        targetMessageId: String? = null,
        forceWrite: Boolean? = null,
        confirmedPreflightRunId: String? = null,
        resultDelivery: MemoryOperationResultDelivery? = null,
    ): String =
        schedule(MemoryOperationKind.REMEMBER, resultDelivery) {
            preparer.prepareRememberMessage(
                conversationIdValue = conversationIdValue,
                targetMessageId = targetMessageId,
                forceWrite = forceWrite,
                confirmedPreflightRunId = confirmedPreflightRunId,
                namespace = namespace,
            )
        }

    suspend fun rememberThread(
        conversationIdValue: String,
        namespace: MemoryNamespace,
    ): List<MemoryOperationQueuedResult> =
        preparer.prepareRememberThread(
            conversationIdValue = conversationIdValue,
            namespace = namespace,
        ).map { prepared ->
            enqueue(prepared)
        }

    suspend fun rememberProvidedContent(
        conversationIdValue: String?,
        namespace: MemoryNamespace,
        text: String? = null,
        workspaceFile: WorkspacePathReference? = null,
        workspacePathAccess: WorkspacePathAccessContext? = null,
        rawUrl: String? = null,
        documentType: String? = null,
        title: String? = null,
        sourceRef: String? = null,
        forceWrite: Boolean? = null,
        confirmedPreflightRunId: String? = null,
        mode: String? = null,
        writeSurface: MemoryWriteSurface = MemoryWriteSurface.CHAT_TOOL,
        resultDelivery: MemoryOperationResultDelivery? = null,
    ): String =
        schedule(MemoryOperationKind.REMEMBER, resultDelivery) {
            preparer.prepareRememberProvidedContent(
                conversationIdValue = conversationIdValue,
                text = text,
                workspaceFile = workspaceFile,
                workspacePathAccess = workspacePathAccess,
                rawUrl = rawUrl,
                documentType = documentType,
                title = title,
                sourceRef = sourceRef,
                forceWrite = forceWrite,
                confirmedPreflightRunId = confirmedPreflightRunId,
                mode = mode,
                namespace = namespace,
                writeSurface = writeSurface,
            )
        }

    suspend fun forgetSource(
        conversationIdValue: String?,
        namespace: MemoryNamespace,
        sourceIdValue: String,
        resultDelivery: MemoryOperationResultDelivery? = null,
    ): String =
        schedule(MemoryOperationKind.FORGET_SOURCE, resultDelivery) {
            preparer.prepareForgetSource(
                conversationIdValue = conversationIdValue,
                namespace = namespace,
                sourceIdValue = sourceIdValue,
            )
        }

    suspend fun enrichMessage(
        conversationIdValue: String,
        namespace: MemoryNamespace,
        targetMessageId: String? = null,
        resultDelivery: MemoryOperationResultDelivery? = null,
    ): String =
        schedule(MemoryOperationKind.ENRICH_CONTEXT, resultDelivery) {
            preparer.prepareEnrichMessage(
                conversationIdValue = conversationIdValue,
                namespace = namespace,
                targetMessageId = targetMessageId,
            )
        }

    suspend fun enrichProvidedContext(
        conversationIdValue: String?,
        namespace: MemoryNamespace,
        contextText: String,
        mode: String? = null,
        resultDelivery: MemoryOperationResultDelivery? = null,
    ): String =
        schedule(MemoryOperationKind.ENRICH_CONTEXT, resultDelivery) {
            preparer.prepareEnrichProvidedContext(
                conversationIdValue = conversationIdValue,
                namespace = namespace,
                contextText = contextText,
                mode = mode,
            )
        }

    suspend fun answerMessage(
        conversationIdValue: String,
        namespace: MemoryNamespace,
        targetMessageId: String? = null,
        resultDelivery: MemoryOperationResultDelivery? = null,
    ): String =
        schedule(MemoryOperationKind.ANSWER_QUESTION, resultDelivery) {
            preparer.prepareAnswerMessage(
                conversationIdValue = conversationIdValue,
                namespace = namespace,
                targetMessageId = targetMessageId,
            )
        }

    suspend fun answerProvidedQuestion(
        conversationIdValue: String?,
        namespace: MemoryNamespace,
        questionText: String,
        mode: String? = null,
        resultDelivery: MemoryOperationResultDelivery? = null,
    ): String =
        schedule(MemoryOperationKind.ANSWER_QUESTION, resultDelivery) {
            preparer.prepareAnswerProvidedQuestion(
                conversationIdValue = conversationIdValue,
                namespace = namespace,
                questionText = questionText,
                mode = mode,
            )
        }

    suspend fun scheduleMaintenance(
        action: MemoryMaintenanceAction,
        targetKind: MemoryMaintenanceTargetKind,
        targetValue: String,
        executionConversationId: com.gromozeka.domain.model.Conversation.Id,
        namespace: MemoryNamespace,
        embeddingRebuildMode: MemoryEmbeddingRebuildMode = MemoryEmbeddingRebuildMode.FULL,
    ): MemoryMaintenanceQueuedResult {
        val queued = enqueue(
            PreparedMemoryOperation(
                request = MemoryOperationRequest.Maintenance(
                    namespace = namespace,
                    action = action,
                    targetKind = targetKind,
                    targetValue = targetValue,
                    executionConversationId = executionConversationId,
                    embeddingRebuildMode = embeddingRebuildMode,
                ),
                summary = "${action.displayName} queued",
            )
        )
        return MemoryMaintenanceQueuedResult(
            runId = queued.runId,
            action = action,
            targetKind = targetKind,
            targetValue = targetValue,
            namespace = namespace,
            conversationId = executionConversationId,
            queueSize = queued.queueSize,
        )
    }

    private suspend fun schedule(
        operation: MemoryOperationKind,
        resultDelivery: MemoryOperationResultDelivery?,
        prepare: suspend () -> PreparedMemoryOperation,
    ): String = try {
        MemoryToolResultRenderer.operationQueuedResultJsonString(enqueue(prepare(), resultDelivery))
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

    private suspend fun enqueue(
        prepared: PreparedMemoryOperation,
        resultDelivery: MemoryOperationResultDelivery? = null,
    ): MemoryOperationQueuedResult {
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
                resultDelivery?.let { delivery ->
                    put(
                        MEMORY_OPERATION_RESULT_DELIVERY_METADATA_KEY,
                        operationJson.encodeToJsonElement(MemoryOperationResultDelivery.serializer(), delivery),
                    )
                }
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
        publishLifecycleEvent(run)
        check(
            operationQueue.enqueue(
                MemoryOperationJob(
                    runId = run.id,
                    operation = prepared.request.kind,
                    namespace = prepared.namespace,
                )
            )
        ) {
            "New memory operation was already scheduled: ${run.id.value}"
        }

        val queueSize = memoryStore.findRunsByStatuses(
            statuses = setOf(MemoryRun.Status.QUEUED),
            runTypes = MEMORY_OPERATION_RUN_TYPES,
        ).size
        log.info {
            "Memory operation queued: run=${run.id.value} operation=${prepared.request.kind.wireName} " +
                "namespace=${prepared.namespace.value} queueSize=$queueSize"
        }
        return MemoryOperationQueuedResult(
            runId = run.id,
            operation = prepared.request.kind,
            namespace = prepared.namespace,
            queueSize = queueSize,
            resultDelivery = resultDelivery,
        )
    }

    private suspend fun publishLifecycleEvent(run: MemoryRun) {
        try {
            lifecycleEventPublisher.publish(
                MemoryRunLifecycleEvent(
                    runId = run.id,
                    status = run.status,
                    occurredAt = Clock.System.now(),
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn(error) {
                "Memory lifecycle event publish failed; startup reconciliation can recover it: " +
                    "run=${run.id.value} status=${run.status} error=${error.message}"
            }
        }
    }

}
