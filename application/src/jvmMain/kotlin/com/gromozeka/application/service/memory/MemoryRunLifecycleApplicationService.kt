package com.gromozeka.application.service.memory

import com.gromozeka.application.service.ConversationRuntimeDispatcher
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeMemoryOperation
import com.gromozeka.domain.service.MemoryRunLifecycleEventConsumer
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicBoolean

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.server.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class MemoryRunLifecycleApplicationService(
    private val eventConsumer: MemoryRunLifecycleEventConsumer,
    private val memoryStore: MemoryStore,
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val runtimeDispatcher: ConversationRuntimeDispatcher,
    @Qualifier("applicationScope") private val coroutineScope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val started = AtomicBoolean(false)

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        coroutineScope.launch {
            eventConsumer.deliveries.collect { delivery ->
                try {
                    memoryStore.findRunById(delivery.event.runId)?.let { run ->
                        handle(run)
                    }
                    delivery.acknowledge()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log.warn(error) {
                        "Memory lifecycle event handling failed; DB reconciliation will retry it: " +
                            "run=${delivery.event.runId.value} error=${error.message}"
                    }
                    delivery.reject()
                }
            }
        }
        coroutineScope.launch {
            while (currentCoroutineContext().isActive) {
                try {
                    reconcileConversationDeliveries()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log.warn(error) { "Memory lifecycle reconciliation failed: ${error.message}" }
                }
                delay(RECONCILIATION_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun reconcileConversationDeliveries() {
        memoryStore.findRunsByMetadataKeys(
            statuses = MemoryRun.Status.entries.toSet(),
            runTypes = MEMORY_OPERATION_RUN_TYPES,
            requiredKey = MEMORY_OPERATION_RESULT_DELIVERY_METADATA_KEY,
            absentKey = MEMORY_OPERATION_RESULT_DELIVERY_STATE_METADATA_KEY,
        ).forEach { run ->
            handle(run)
        }
    }

    private suspend fun handle(run: MemoryRun) {
        val delivery = run.resultDeliveryOrNull() ?: return
        val operation = run.metadata[MEMORY_OPERATION_KIND_METADATA_KEY]
            ?.jsonPrimitive
            ?.content
            ?: run.runType.name.lowercase()
        val projectionChanged = runtimeCoordinator.upsertMemoryOperation(
            conversationId = delivery.conversationId,
            operation = ConversationRuntimeMemoryOperation(
                runId = run.id,
                operation = operation,
                status = run.status,
                summary = run.summary,
                progress = run.progress,
                startedAt = run.startedAt,
                completedAt = run.completedAt,
                updatedAt = run.completedAt ?: run.startedAt ?: run.createdAt,
            ),
        )
        if (projectionChanged) {
            runtimeDispatcher.publishSnapshot(delivery.conversationId)
        }

        if (!run.status.isTerminal() || run.resultDeliveryStateOrNull() != null) {
            return
        }
        if (run.status == MemoryRun.Status.CANCELLED) {
            markResultDelivery(run, MEMORY_OPERATION_RESULT_DELIVERY_CANCELLED)
            return
        }
        val runtimeState = runtimeCoordinator.find(delivery.conversationId)
        if (runtimeState?.controlState == ConversationExecutionState.ControlState.STOPPING ||
            runtimeState?.controlState == ConversationExecutionState.ControlState.INTERRUPTING
        ) {
            markResultDelivery(run, MEMORY_OPERATION_RESULT_DELIVERY_CANCELLED)
            return
        }

        val submitted = runtimeDispatcher.submitMemoryRunCompletion(
            conversationId = delivery.conversationId,
            runId = run.id,
            agentDefinitionId = delivery.agentDefinitionId,
            statusToolName = delivery.statusToolName,
        )
        val stateAfterSubmit = runtimeCoordinator.find(delivery.conversationId)
        val deliveryState = if (!submitted &&
            (stateAfterSubmit?.controlState == ConversationExecutionState.ControlState.STOPPING ||
                stateAfterSubmit?.controlState == ConversationExecutionState.ControlState.INTERRUPTING)
        ) {
            MEMORY_OPERATION_RESULT_DELIVERY_CANCELLED
        } else {
            MEMORY_OPERATION_RESULT_DELIVERY_SCHEDULED
        }
        markResultDelivery(run, deliveryState)
    }

    private suspend fun markResultDelivery(run: MemoryRun, state: String) {
        repeat(3) {
            val current = memoryStore.findRunById(run.id) ?: return
            if (current.resultDeliveryStateOrNull() != null) return
            val replacement = current.copy(
                metadata = JsonObject(
                    current.metadata + (MEMORY_OPERATION_RESULT_DELIVERY_STATE_METADATA_KEY to JsonPrimitive(state))
                ),
            )
            if (memoryStore.replaceRunIfUnchanged(current, replacement)) return
        }
        error("Failed to persist memory result delivery state: run=${run.id.value} state=$state")
    }

    private fun MemoryRun.resultDeliveryOrNull(): MemoryOperationResultDelivery? =
        metadata[MEMORY_OPERATION_RESULT_DELIVERY_METADATA_KEY]?.let { encoded ->
            json.decodeFromJsonElement(MemoryOperationResultDelivery.serializer(), encoded)
        }

    private fun MemoryRun.resultDeliveryStateOrNull(): String? =
        (metadata[MEMORY_OPERATION_RESULT_DELIVERY_STATE_METADATA_KEY] as? JsonPrimitive)?.content

    private fun MemoryRun.Status.isTerminal(): Boolean =
        this == MemoryRun.Status.NEEDS_INPUT ||
            this == MemoryRun.Status.SUCCESS ||
            this == MemoryRun.Status.FAILED ||
            this == MemoryRun.Status.PARTIAL ||
            this == MemoryRun.Status.CANCELLED

    private companion object {
        const val RECONCILIATION_INTERVAL_MILLIS = 5_000L
    }
}
