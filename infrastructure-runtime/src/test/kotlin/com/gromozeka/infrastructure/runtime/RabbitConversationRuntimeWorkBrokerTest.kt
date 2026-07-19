package com.gromozeka.infrastructure.runtime

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkerAffinity
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RabbitConversationRuntimeWorkBrokerTest {

    @Test
    fun `rabbit routing rejects unknown capability profiles`() {
        assertFailsWith<IllegalStateException> {
            RabbitRuntimeWorkRoute.from(
                ConversationRuntimeTaskRequirements(
                    capabilities = setOf(ConversationRuntimeWorkerCapability.CONVERSATION_TURN),
                )
            )
        }
    }

    @Test
    fun `worker consumes tasks addressed to its stable worker affinity`() {
        val descriptor = ConversationRuntimeWorkerDescriptor(
            id = ConversationRuntimeWorkerId("local-worker"),
            capabilities = setOf(
                ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
                ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
            ),
        )
        val workerRoute = RabbitRuntimeWorkRoute(
            lane = RabbitRuntimeWorkLane.LOCAL_AGENT,
            affinity = ConversationRuntimeWorkerAffinity(
                kind = ConversationRuntimeWorkerAffinity.Kind.WORKER,
                value = descriptor.id.value,
            ),
        )

        assertTrue(workerRoute in descriptor.consumerRoutes())
    }

    @Test
    fun `rabbit work queue delivers only work matching worker capabilities`() = runBlocking {
        if (System.getenv("GROMOZEKA_RABBIT_RUNTIME_TEST") != "true") {
            return@runBlocking
        }

        val connectionFactory = CachingConnectionFactory("localhost", 5672)
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE)
        val admin = RabbitAdmin(connectionFactory)
        val exchangeName = "gromozeka.runtime.work.test.${uuid7()}"
        val queueName = "gromozeka.runtime.work.test.${uuid7()}"
        val conversationId = Conversation.Id("conversation-1")
        val llmItem = workItem(
            conversationId = conversationId,
            taskId = "llm-task",
            capabilities = setOf(
                ConversationRuntimeWorkerCapability.LLM_RUNTIME,
                ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
            ),
        )
        val turnItem = workItem(
            conversationId = conversationId,
            taskId = "turn-task",
            capabilities = setOf(
                ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
            ),
        )
        val projectAffinity = ConversationRuntimeWorkerAffinity(
            kind = ConversationRuntimeWorkerAffinity.Kind.PROJECT,
            value = "project-1",
        )
        val localToolItem = workItem(
            conversationId = conversationId,
            taskId = "local-tool-task",
            capabilities = setOf(
                ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
                ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
            ),
            affinity = projectAffinity,
        )
        val wrongProjectAffinity = ConversationRuntimeWorkerAffinity(
            kind = ConversationRuntimeWorkerAffinity.Kind.PROJECT,
            value = "project-2",
        )
        val topology = RabbitConversationRuntimeWorkTopology(
            connectionFactory = connectionFactory,
            exchangeName = exchangeName,
            queueName = queueName,
            shardCount = 1,
            deadLetterExchangeName = "$exchangeName.dlx",
        )
        val publisher = RabbitConversationRuntimeWorkPublisher(
            rabbitTemplate = RabbitTemplate(connectionFactory),
            topology = topology,
        )
        val turnWorkerDescriptor = ConversationRuntimeWorkerDescriptor(
            id = ConversationRuntimeWorkerId("turn-worker"),
            capabilities = setOf(
                ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
            ),
        )
        val localWorkerDescriptor = ConversationRuntimeWorkerDescriptor(
            id = ConversationRuntimeWorkerId("local-worker"),
            capabilities = setOf(
                ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
                ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
            ),
            affinities = setOf(projectAffinity),
        )
        val wrongProjectWorkerDescriptor = ConversationRuntimeWorkerDescriptor(
            id = ConversationRuntimeWorkerId("wrong-project-worker"),
            capabilities = setOf(
                ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
                ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
            ),
            affinities = setOf(wrongProjectAffinity),
        )
        val turnConsumer = RabbitConversationRuntimeWorkConsumer(
            connectionFactory = connectionFactory,
            rabbitTemplate = RabbitTemplate(connectionFactory),
            topology = topology,
            maxRedeliveries = 8,
            runtimeWorkerDescriptor = turnWorkerDescriptor,
        )
        val localConsumer = RabbitConversationRuntimeWorkConsumer(
            connectionFactory = connectionFactory,
            rabbitTemplate = RabbitTemplate(connectionFactory),
            topology = topology,
            maxRedeliveries = 8,
            runtimeWorkerDescriptor = localWorkerDescriptor,
        )
        val wrongProjectConsumer = RabbitConversationRuntimeWorkConsumer(
            connectionFactory = connectionFactory,
            rabbitTemplate = RabbitTemplate(connectionFactory),
            topology = topology,
            maxRedeliveries = 8,
            runtimeWorkerDescriptor = wrongProjectWorkerDescriptor,
        )

        try {
            turnConsumer.start()

            publisher.submit(llmItem)
            assertNull(withTimeoutOrNull(300) { turnConsumer.deliveries.first() })

            publisher.submit(turnItem)
            val delivery = withTimeout(2_000) { turnConsumer.deliveries.first() }
            assertEquals(turnItem, delivery.item)
            assertEquals(0, delivery.redeliveryCount)
            delivery.redeliver()
            val redelivery = withTimeout(2_000) { turnConsumer.deliveries.first() }
            assertEquals(turnItem, redelivery.item)
            assertEquals(1, redelivery.redeliveryCount)
            redelivery.acknowledge()

            publisher.submit(localToolItem)
            assertNull(withTimeoutOrNull(300) { turnConsumer.deliveries.first() })
            wrongProjectConsumer.start()
            assertNull(withTimeoutOrNull(300) { wrongProjectConsumer.deliveries.first() })

            localConsumer.start()
            val localDelivery = withTimeout(2_000) { localConsumer.deliveries.first() }
            assertEquals(localToolItem, localDelivery.item)
            localDelivery.acknowledge()
        } finally {
            turnConsumer.stop()
            localConsumer.stop()
            wrongProjectConsumer.stop()
            val declaredRoutes = buildSet {
                addAll(turnWorkerDescriptor.consumerRoutes())
                addAll(localWorkerDescriptor.consumerRoutes())
                addAll(wrongProjectWorkerDescriptor.consumerRoutes())
                add(RabbitRuntimeWorkRoute.from(llmItem.requirements))
                add(RabbitRuntimeWorkRoute.from(localToolItem.requirements))
            }
            declaredRoutes
                .flatMap(topology::queueNames)
                .forEach { routeQueueName ->
                    admin.deleteQueue(routeQueueName)
                    admin.deleteQueue("$routeQueueName.dlq")
                }
            admin.deleteExchange(exchangeName)
            admin.deleteExchange("$exchangeName.dlx")
            connectionFactory.destroy()
        }
    }

    private fun workItem(
        conversationId: Conversation.Id,
        taskId: String,
        capabilities: Set<ConversationRuntimeWorkerCapability>,
        affinity: ConversationRuntimeWorkerAffinity? = null,
    ): ConversationRuntimeWorkItem =
        ConversationRuntimeWorkItem(
            conversationId = conversationId,
            reason = ConversationRuntimeWorkItem.Reason.TASK_SUBMITTED,
            taskId = ConversationRuntimeTask.Id(taskId),
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = capabilities,
                affinity = affinity,
            ),
            createdAt = Clock.System.now(),
        )

}
