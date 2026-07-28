package com.gromozeka.infrastructure.runtime

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorLifecycleEvent
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandMonitorLifecycleEventBusTest {
    @Test
    fun `in-memory bus delivers monitor lifecycle event`() = runBlocking {
        val bus = InMemoryCommandMonitorLifecycleEventBus()
        val event = CommandMonitorLifecycleEvent(
            conversationId = Conversation.Id("conversation-1"),
            monitorId = CommandMonitor.Id("monitor-1"),
            kind = CommandMonitorLifecycleEvent.Kind.EVENTS_AVAILABLE,
            occurredAt = Instant.fromEpochMilliseconds(1_000),
        )

        bus.publish(event)
        val delivery = withTimeout(1_000) { bus.deliveries.first() }

        assertEquals(event, delivery.event)
        delivery.acknowledge()
    }

    @Test
    fun `rabbit bus publishes and acknowledges monitor lifecycle event`() = runBlocking {
        if (System.getenv("GROMOZEKA_RABBIT_RUNTIME_TEST") != "true") {
            return@runBlocking
        }

        val connectionFactory = CachingConnectionFactory("localhost", 5672)
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE)
        val exchangeName = "gromozeka.command-monitor.events.test.${uuid7()}"
        val queueName = "gromozeka.command-monitor.events.test.${uuid7()}"
        val topology = RabbitCommandMonitorLifecycleEventTopology(
            connectionFactory = connectionFactory,
            exchangeName = exchangeName,
            queueName = queueName,
            routingKey = "command-monitor",
        )
        val publisher = RabbitCommandMonitorLifecycleEventPublisher(
            rabbitTemplate = RabbitTemplate(connectionFactory),
            topology = topology,
        )
        val consumer = RabbitCommandMonitorLifecycleEventConsumer(connectionFactory, topology)
        val event = CommandMonitorLifecycleEvent(
            conversationId = Conversation.Id("conversation-1"),
            monitorId = CommandMonitor.Id("monitor-1"),
            kind = CommandMonitorLifecycleEvent.Kind.TERMINAL,
            occurredAt = Instant.fromEpochMilliseconds(1_000),
        )
        try {
            consumer.start()
            publisher.publish(event)
            val delivery = withTimeout(2_000) { consumer.deliveries.first() }

            assertEquals(event, delivery.event)
            delivery.acknowledge()
        } finally {
            consumer.stop()
            val admin = RabbitAdmin(connectionFactory)
            admin.deleteQueue(queueName)
            admin.deleteExchange(exchangeName)
            connectionFactory.destroy()
        }
    }
}
