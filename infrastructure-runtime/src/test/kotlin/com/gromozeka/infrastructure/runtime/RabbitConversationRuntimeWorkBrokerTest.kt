package com.gromozeka.infrastructure.runtime

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeExecutorDescriptor
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeServerSessionId
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
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

class RabbitConversationRuntimeWorkBrokerTest {

    @Test
    fun `rabbit routing sends orchestration work to Server`() {
        val route = RabbitRuntimeWorkRoute.from(
            ConversationRuntimeTaskRequirements(
                capabilities = setOf(ConversationRuntimeCapability.CONVERSATION_TURN),
                target = ConversationRuntimeTaskTarget.Server,
            )
        )

        assertEquals(RabbitRuntimeWorkRoute.Server, route)
    }

    @Test
    fun `rabbit routing sends Worker work to its exact stable id`() {
        val workerId = ConversationRuntimeWorkerId("claude-worker")
        val route = RabbitRuntimeWorkRoute.from(
            ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeCapability.TOOL_EXECUTION,
                    ConversationRuntimeCapability.LOCAL_AGENT_TOOL,
                ),
                target = ConversationRuntimeTaskTarget.Worker(
                    workerId = workerId,
                    workspaceMountId = WorkspaceMount.Id("mount-1"),
                ),
            )
        )

        assertEquals(RabbitRuntimeWorkRoute.Worker(workerId), route)
    }

    @Test
    fun `rabbit work queue delivers only to the exact Server or Worker route`() = runBlocking {
        if (System.getenv("GROMOZEKA_RABBIT_RUNTIME_TEST") != "true") {
            return@runBlocking
        }

        val connectionFactory = CachingConnectionFactory("localhost", 5672)
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE)
        val admin = RabbitAdmin(connectionFactory)
        val exchangeName = "gromozeka.runtime.work.test.${uuid7()}"
        val queueName = "gromozeka.runtime.work.test.${uuid7()}"
        val topology = RabbitConversationRuntimeWorkTopology(
            connectionFactory = connectionFactory,
            exchangeName = exchangeName,
            queueNamePrefix = queueName,
            deadLetterExchangeName = "$exchangeName.dlx",
        )
        val publisher = RabbitConversationRuntimeWorkPublisher(
            rabbitTemplate = RabbitTemplate(connectionFactory),
            topology = topology,
        )
        val serverDescriptor = ConversationRuntimeExecutorDescriptor(
            identity = ConversationRuntimeExecutorIdentity.Server(
                ConversationRuntimeServerSessionId("server-session")
            ),
            capabilities = setOf(ConversationRuntimeCapability.CONVERSATION_TURN),
        )
        val targetWorkerId = ConversationRuntimeWorkerId("target-worker")
        val targetWorkerDescriptor = workerDescriptor(targetWorkerId)
        val otherWorkerDescriptor = workerDescriptor(ConversationRuntimeWorkerId("other-worker"))
        val serverConsumer = consumer(connectionFactory, topology, serverDescriptor)
        val targetWorkerConsumer = consumer(connectionFactory, topology, targetWorkerDescriptor)
        val otherWorkerConsumer = consumer(connectionFactory, topology, otherWorkerDescriptor)
        val conversationId = Conversation.Id("conversation-1")
        val serverItem = workItem(
            conversationId = conversationId,
            taskId = "server-task",
            capabilities = setOf(ConversationRuntimeCapability.CONVERSATION_TURN),
            target = ConversationRuntimeTaskTarget.Server,
        )
        val workerItem = workItem(
            conversationId = conversationId,
            taskId = "worker-task",
            capabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION),
            target = ConversationRuntimeTaskTarget.Worker(targetWorkerId),
        )

        try {
            serverConsumer.start()
            otherWorkerConsumer.start()

            publisher.submit(workerItem)
            assertNull(withTimeoutOrNull(300) { serverConsumer.deliveries.first() })
            assertNull(withTimeoutOrNull(300) { otherWorkerConsumer.deliveries.first() })

            targetWorkerConsumer.start()
            val workerDelivery = withTimeout(2_000) { targetWorkerConsumer.deliveries.first() }
            assertEquals(workerItem, workerDelivery.item)
            workerDelivery.acknowledge()

            publisher.submit(serverItem)
            val serverDelivery = withTimeout(2_000) { serverConsumer.deliveries.first() }
            assertEquals(serverItem, serverDelivery.item)
            serverDelivery.acknowledge()
        } finally {
            serverConsumer.stop()
            targetWorkerConsumer.stop()
            otherWorkerConsumer.stop()
            setOf(
                RabbitRuntimeWorkRoute.Server,
                RabbitRuntimeWorkRoute.Worker(targetWorkerId),
                RabbitRuntimeWorkRoute.Worker(ConversationRuntimeWorkerId("other-worker")),
            ).map(topology::queueName).forEach { routeQueueName ->
                admin.deleteQueue(routeQueueName)
                admin.deleteQueue("$routeQueueName.dlq")
            }
            admin.deleteExchange(exchangeName)
            admin.deleteExchange("$exchangeName.dlx")
            connectionFactory.destroy()
        }
    }

    private fun consumer(
        connectionFactory: CachingConnectionFactory,
        topology: RabbitConversationRuntimeWorkTopology,
        descriptor: ConversationRuntimeExecutorDescriptor,
    ): RabbitConversationRuntimeWorkConsumer =
        RabbitConversationRuntimeWorkConsumer(
            connectionFactory = connectionFactory,
            rabbitTemplate = RabbitTemplate(connectionFactory),
            topology = topology,
            maxRedeliveries = 8,
            runtimeExecutorDescriptor = descriptor,
        )

    private fun workerDescriptor(
        workerId: ConversationRuntimeWorkerId,
    ): ConversationRuntimeExecutorDescriptor =
        ConversationRuntimeExecutorDescriptor(
            identity = ConversationRuntimeExecutorIdentity.Worker(
                ConversationRuntimeWorkerIdentity(
                    workerId = workerId,
                    sessionId = ConversationRuntimeWorkerSessionId("${workerId.value}-session"),
                )
            ),
            capabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION),
        )

    private fun workItem(
        conversationId: Conversation.Id,
        taskId: String,
        capabilities: Set<ConversationRuntimeCapability>,
        target: ConversationRuntimeTaskTarget,
    ): ConversationRuntimeWorkItem =
        ConversationRuntimeWorkItem(
            conversationId = conversationId,
            reason = ConversationRuntimeWorkItem.Reason.TASK_SUBMITTED,
            taskId = ConversationRuntimeTask.Id(taskId),
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = capabilities,
                target = target,
            ),
            createdAt = Clock.System.now(),
        )
}
