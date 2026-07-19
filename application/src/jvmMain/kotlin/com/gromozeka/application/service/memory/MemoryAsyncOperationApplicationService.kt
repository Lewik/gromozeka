package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
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
) {
    private val log = KLoggers.logger(this)
    private val operationJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        classDiscriminator = "requestType"
    }

    suspend fun rememberMessage(
        conversationIdValue: String,
        targetMessageId: String? = null,
        forceWrite: Boolean? = null,
        confirmedPreflightRunId: String? = null,
        namespaceValue: String? = null,
    ): String =
        schedule(MemoryOperationKind.REMEMBER) {
            preparer.prepareRememberMessage(
                conversationIdValue = conversationIdValue,
                targetMessageId = targetMessageId,
                forceWrite = forceWrite,
                confirmedPreflightRunId = confirmedPreflightRunId,
                namespaceValue = namespaceValue,
            )
        }

    suspend fun rememberThread(
        conversationIdValue: String,
        namespaceValue: String? = null,
    ): List<MemoryOperationQueuedResult> =
        preparer.prepareRememberThread(
            conversationIdValue = conversationIdValue,
            namespaceValue = namespaceValue,
        ).map { prepared ->
            enqueue(prepared)
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
            preparer.prepareRememberProvidedContent(
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
            preparer.prepareEnrichMessage(
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
            preparer.prepareEnrichProvidedContext(
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
            preparer.prepareAnswerMessage(
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
            preparer.prepareAnswerProvidedQuestion(
                conversationIdValue = conversationIdValue,
                questionText = questionText,
                mode = mode,
                namespaceValue = namespaceValue,
            )
        }

    suspend fun scheduleMaintenance(
        action: MemoryMaintenanceAction,
        targetKind: MemoryMaintenanceTargetKind,
        targetValue: String,
        executionConversationId: com.gromozeka.domain.model.Conversation.Id,
        namespace: com.gromozeka.domain.model.memory.MemoryNamespace,
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
        prepare: suspend () -> PreparedMemoryOperation,
    ): String = try {
        MemoryToolResultRenderer.operationQueuedResultJsonString(enqueue(prepare()))
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

    private suspend fun enqueue(prepared: PreparedMemoryOperation): MemoryOperationQueuedResult {
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
        )
    }

}
