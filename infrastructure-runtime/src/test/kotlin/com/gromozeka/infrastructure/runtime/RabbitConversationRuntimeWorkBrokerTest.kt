package com.gromozeka.infrastructure.runtime

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkerEnvironmentProfile
import com.gromozeka.domain.service.WorkerNativeShell
import com.gromozeka.domain.service.WorkerOperatingSystem
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
    fun `rabbit routing rejects unknown shared capability profiles`() {
        assertFailsWith<IllegalStateException> {
            RabbitRuntimeWorkRoute.from(
                ConversationRuntimeTaskRequirements(
                    capabilities = setOf(ConversationRuntimeWorkerCapability.TOOL_EXECUTION),
                )
            )
        }
    }

    @Test
    fun `rabbit routing sends targeted work directly to its worker`() {
        val workerId = ConversationRuntimeWorkerId("claude-worker")

        val route = RabbitRuntimeWorkRoute.from(
            ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
                    ConversationRuntimeWorkerCapability.LLM_RUNTIME,
                ),
                target = ConversationRuntimeTaskTarget(workerId),
            )
        )

        assertEquals(RabbitRuntimeWorkRoute.Worker(workerId), route)
    }

    @Test
    fun `rabbit routing supports conversation incident work`() {
        val route = RabbitRuntimeWorkRoute.from(
            ConversationRuntimeTaskRequirements(
                capabilities = setOf(ConversationRuntimeWorkerCapability.CONVERSATION_TURN),
            )
        )

        assertEquals(RabbitRuntimeWorkRoute.Shared(RabbitRuntimeWorkLane.INCIDENT), route)
    }

    @Test
    fun `worker consumes tasks addressed to its stable worker id`() {
        val descriptor = ConversationRuntimeWorkerDescriptor(
            id = ConversationRuntimeWorkerId("local-worker"),
            capabilities = setOf(
                ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
                ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
            ),
            environmentProfile = testWorkerEnvironmentProfile(),
        )
        val workerRoute = RabbitRuntimeWorkRoute.Worker(descriptor.id)

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
        val localToolItem = workItem(
            conversationId = conversationId,
            taskId = "local-tool-task",
            capabilities = setOf(
                ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
                ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
                ConversationRuntimeWorkerCapability.LLM_RUNTIME,
            ),
            target = ConversationRuntimeTaskTarget(
                workerId = ConversationRuntimeWorkerId("local-worker"),
                workspaceMountId = WorkspaceMount.Id("mount-1"),
            ),
        )
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
        val turnWorkerDescriptor = ConversationRuntimeWorkerDescriptor(
            id = ConversationRuntimeWorkerId("turn-worker"),
            capabilities = setOf(
                ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
            ),
            environmentProfile = testWorkerEnvironmentProfile(),
        )
        val localWorkerDescriptor = ConversationRuntimeWorkerDescriptor(
            id = ConversationRuntimeWorkerId("local-worker"),
            capabilities = setOf(
                ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
                ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
                ConversationRuntimeWorkerCapability.LLM_RUNTIME,
            ),
            environmentProfile = testWorkerEnvironmentProfile(),
        )
        val wrongWorkerDescriptor = ConversationRuntimeWorkerDescriptor(
            id = ConversationRuntimeWorkerId("wrong-worker"),
            capabilities = setOf(
                ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
                ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
                ConversationRuntimeWorkerCapability.LLM_RUNTIME,
            ),
            environmentProfile = testWorkerEnvironmentProfile(),
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
        val wrongWorkerConsumer = RabbitConversationRuntimeWorkConsumer(
            connectionFactory = connectionFactory,
            rabbitTemplate = RabbitTemplate(connectionFactory),
            topology = topology,
            maxRedeliveries = 8,
            runtimeWorkerDescriptor = wrongWorkerDescriptor,
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
            wrongWorkerConsumer.start()
            assertNull(withTimeoutOrNull(300) { wrongWorkerConsumer.deliveries.first() })

            localConsumer.start()
            val localDelivery = withTimeout(2_000) { localConsumer.deliveries.first() }
            assertEquals(localToolItem, localDelivery.item)
            localDelivery.acknowledge()
        } finally {
            turnConsumer.stop()
            localConsumer.stop()
            wrongWorkerConsumer.stop()
            val declaredRoutes = buildSet {
                addAll(turnWorkerDescriptor.consumerRoutes())
                addAll(localWorkerDescriptor.consumerRoutes())
                addAll(wrongWorkerDescriptor.consumerRoutes())
                add(RabbitRuntimeWorkRoute.from(llmItem.requirements))
                add(RabbitRuntimeWorkRoute.from(localToolItem.requirements))
            }
            declaredRoutes
                .map(topology::queueName)
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
        target: ConversationRuntimeTaskTarget? = null,
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

private fun testWorkerEnvironmentProfile(): WorkerEnvironmentProfile =
    WorkerEnvironmentProfile(
        observedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(1),
        operatingSystem = WorkerOperatingSystem(
            family = WorkerOperatingSystem.Family.LINUX,
            name = "Test Linux",
            version = "1",
        ),
        architecture = "x86_64",
        nativeShell = WorkerNativeShell(WorkerNativeShell.Kind.POSIX_SH, "/bin/sh"),
        timezoneId = "UTC",
        localeTag = "en-US",
        logicalProcessorCount = 4,
        totalMemoryBytes = 8_589_934_592,
        availableExecutables = listOf("sh"),
    )
