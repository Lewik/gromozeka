package com.gromozeka.application.service.memory

import com.gromozeka.application.service.ConversationRuntimeDispatcher
import com.gromozeka.application.service.InMemoryConversationRuntimeCoordinator
import com.gromozeka.application.service.InMemoryConversationRuntimeEventBus
import com.gromozeka.application.service.InMemoryConversationRuntimeWorkerRegistry
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.MemoryRunLifecycleEvent
import com.gromozeka.domain.service.MemoryRunLifecycleEventConsumer
import com.gromozeka.domain.service.MemoryRunLifecycleEventDelivery
import com.gromozeka.domain.service.MemoryRunLifecycleEventPublisher
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MemoryRunLifecycleApplicationServiceTest {
    @Test
    fun `terminal memory run is projected and scheduled exactly once`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val memoryStore = InMemoryMemoryStore()
        val runtimeCoordinator = InMemoryConversationRuntimeCoordinator()
        val lifecycleEvents = TestMemoryRunLifecycleEventBus()
        val dispatcher = ConversationRuntimeDispatcher(
            runtimeCoordinator = runtimeCoordinator,
            runtimeEventBus = InMemoryConversationRuntimeEventBus(),
            runtimeWorkPublisher = NoOpRuntimeWorkPublisher,
            runtimeWorkerRegistry = InMemoryConversationRuntimeWorkerRegistry(),
            coroutineScope = scope,
        )
        val service = MemoryRunLifecycleApplicationService(
            eventConsumer = lifecycleEvents,
            memoryStore = memoryStore,
            runtimeCoordinator = runtimeCoordinator,
            runtimeDispatcher = dispatcher,
            coroutineScope = scope,
        )
        val conversationId = Conversation.Id("conversation-1")
        val runId = MemoryRun.Id("memory-run-1")
        val now = Clock.System.now()
        val resultDelivery = MemoryOperationResultDelivery(
            conversationId = conversationId,
            agentDefinitionId = AgentDefinition.Id("agent-1"),
            statusToolName = "mcp__memory__memory_run_status",
        )
        val json = Json { encodeDefaults = true }
        val run = MemoryRun(
            id = runId,
            namespace = MemoryNamespace.Global,
            runType = MemoryRun.Type.ANSWER_QUESTION,
            summary = "Memory answer is ready",
            metadata = buildJsonObject {
                put(MEMORY_OPERATION_KIND_METADATA_KEY, MemoryOperationKind.ANSWER_QUESTION.wireName)
                put(
                    MEMORY_OPERATION_RESULT_DELIVERY_METADATA_KEY,
                    json.encodeToJsonElement(MemoryOperationResultDelivery.serializer(), resultDelivery),
                )
            },
            status = MemoryRun.Status.SUCCESS,
            createdAt = now,
            startedAt = now,
            completedAt = now,
        )

        try {
            memoryStore.apply(MemoryUpdateBatch(runs = listOf(run)))
            service.start()
            lifecycleEvents.publish(MemoryRunLifecycleEvent(runId, run.status, now))

            awaitCondition {
                memoryStore.findRunById(runId)
                    ?.metadata
                    ?.get(MEMORY_OPERATION_RESULT_DELIVERY_STATE_METADATA_KEY)
                    ?.jsonPrimitive
                    ?.content == MEMORY_OPERATION_RESULT_DELIVERY_SCHEDULED
            }

            val pendingTask = runtimeCoordinator.listPending(conversationId).single()
            val payload = assertIs<ConversationRuntimeTask.Payload.MemoryRunCompletion>(pendingTask.payload)
            assertEquals(runId, payload.runId)
            assertEquals(resultDelivery.agentDefinitionId, payload.agentDefinitionId)
            assertEquals(resultDelivery.statusToolName, payload.statusToolName)

            val operation = runtimeCoordinator.snapshot(conversationId).memoryOperations.single()
            assertEquals(runId, operation.runId)
            assertEquals(MemoryRun.Status.SUCCESS, operation.status)

            lifecycleEvents.publish(MemoryRunLifecycleEvent(runId, run.status, now))
            delay(100)
            assertEquals(1, runtimeCoordinator.listPending(conversationId).size)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) {
                delay(10)
            }
        }
    }

    private class TestMemoryRunLifecycleEventBus :
        MemoryRunLifecycleEventPublisher,
        MemoryRunLifecycleEventConsumer {
        private val channel = Channel<MemoryRunLifecycleEventDelivery>(Channel.UNLIMITED)

        override val deliveries: Flow<MemoryRunLifecycleEventDelivery> = channel.receiveAsFlow()

        override suspend fun publish(event: MemoryRunLifecycleEvent) {
            channel.send(
                object : MemoryRunLifecycleEventDelivery {
                    override val event: MemoryRunLifecycleEvent = event
                    override suspend fun acknowledge() = Unit
                    override suspend fun redeliver() = Unit
                    override suspend fun reject() = Unit
                }
            )
        }
    }

    private object NoOpRuntimeWorkPublisher : ConversationRuntimeWorkPublisher {
        override suspend fun submit(item: ConversationRuntimeWorkItem) = Unit
    }
}
