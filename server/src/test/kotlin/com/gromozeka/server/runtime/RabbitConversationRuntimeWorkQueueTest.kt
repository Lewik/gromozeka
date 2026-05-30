package com.gromozeka.server.runtime

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
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

class RabbitConversationRuntimeWorkQueueTest {

    @Test
    fun `rabbit work queue delivers only work matching worker capabilities`() = runBlocking {
        if (System.getenv("GROMOZEKA_RABBIT_RUNTIME_TEST") != "true") {
            return@runBlocking
        }

        val connectionFactory = CachingConnectionFactory("localhost", 5672)
        val admin = RabbitAdmin(connectionFactory)
        val exchangeName = "gromozeka.runtime.work.test.${uuid7()}"
        val queueName = "gromozeka.runtime.work.test.${uuid7()}"
        val conversationId = Conversation.Id("conversation-1")
        val llmItem = workItem(
            conversationId = conversationId,
            taskId = "llm-task",
            capabilities = setOf(ConversationRuntimeWorkerCapability.LLM_RUNTIME),
        )
        val turnItem = workItem(
            conversationId = conversationId,
            taskId = "turn-task",
            capabilities = setOf(ConversationRuntimeWorkerCapability.CONVERSATION_TURN),
        )
        val queue = queue(
            connectionFactory = connectionFactory,
            exchangeName = exchangeName,
            queueName = queueName,
            workerDescriptor = ConversationRuntimeWorkerDescriptor(
                id = "worker-1",
                capabilities = setOf(ConversationRuntimeWorkerCapability.CONVERSATION_TURN),
            ),
        )

        try {
            queue.start()

            queue.submit(llmItem)
            assertNull(withTimeoutOrNull(300) { queue.deliveries.first() })

            queue.submit(turnItem)
            val delivery = withTimeout(2_000) { queue.deliveries.first() }
            assertEquals(turnItem, delivery.item)
            delivery.ack()
        } finally {
            queue.stop()
            admin.deleteQueue("$queueName.lane-conversation.aff-global.0")
            admin.deleteQueue("$queueName.lane-conversation.aff-global.0.dlq")
            admin.deleteQueue("$queueName.lane-llm.aff-global.0")
            admin.deleteQueue("$queueName.lane-llm.aff-global.0.dlq")
            admin.deleteExchange(exchangeName)
            admin.deleteExchange("$exchangeName.dlx")
            connectionFactory.destroy()
        }
    }

    private fun workItem(
        conversationId: Conversation.Id,
        taskId: String,
        capabilities: Set<ConversationRuntimeWorkerCapability>,
    ): ConversationRuntimeWorkItem =
        ConversationRuntimeWorkItem(
            conversationId = conversationId,
            reason = ConversationRuntimeWorkItem.Reason.TASK_SUBMITTED,
            taskId = ConversationRuntimeTask.Id(taskId),
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = capabilities,
            ),
            createdAt = Clock.System.now(),
        )

    private fun queue(
        connectionFactory: CachingConnectionFactory,
        exchangeName: String,
        queueName: String,
        workerDescriptor: ConversationRuntimeWorkerDescriptor,
    ): RabbitConversationRuntimeWorkQueue =
        RabbitConversationRuntimeWorkQueue(
            connectionFactory = connectionFactory,
            rabbitTemplate = RabbitTemplate(connectionFactory),
            exchangeName = exchangeName,
            queueName = queueName,
            shardCount = 1,
            deadLetterExchangeName = "$exchangeName.dlx",
            maxRetries = 8,
            runtimeWorkerDescriptor = workerDescriptor,
        )
}
