package com.gromozeka.infrastructure.runtime

import com.gromozeka.domain.service.MemoryRunLifecycleEvent
import com.gromozeka.domain.service.MemoryRunLifecycleEventConsumer
import com.gromozeka.domain.service.MemoryRunLifecycleEventDelivery
import com.gromozeka.domain.service.MemoryRunLifecycleEventPublisher
import klog.KLoggers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.amqp.core.AcknowledgeMode
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitOperations
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicBoolean

@Service
@Primary
@ConditionalOnProperty(
    name = ["gromozeka.runtime.rabbit.enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class InMemoryMemoryRunLifecycleEventBus : MemoryRunLifecycleEventPublisher, MemoryRunLifecycleEventConsumer {
    private val channel = Channel<MemoryRunLifecycleEventDelivery>(Channel.UNLIMITED)

    override val deliveries: Flow<MemoryRunLifecycleEventDelivery> = channel.receiveAsFlow()

    override suspend fun publish(event: MemoryRunLifecycleEvent) {
        channel.send(InMemoryDelivery(event))
    }

    private class InMemoryDelivery(
        override val event: MemoryRunLifecycleEvent,
    ) : MemoryRunLifecycleEventDelivery {
        override suspend fun acknowledge() = Unit
        override suspend fun redeliver() = Unit
        override suspend fun reject() = Unit
    }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.rabbit.enabled"], havingValue = "true")
internal class RabbitMemoryRunLifecycleEventPublisher(
    private val rabbitTemplate: RabbitTemplate,
    private val topology: RabbitMemoryRunLifecycleEventTopology,
) : MemoryRunLifecycleEventPublisher {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    init {
        rabbitTemplate.requireMemoryEventPublisherConfirms()
    }

    override suspend fun publish(event: MemoryRunLifecycleEvent) {
        topology.declare()
        rabbitTemplate.publishMemoryEventConfirmed { operations ->
            operations.convertAndSend(topology.exchangeName, topology.routingKey, json.encodeToString(event))
        }
    }
}

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.rabbit.enabled", "gromozeka.runtime.server.enabled"],
    havingValue = "true",
)
internal class RabbitMemoryRunLifecycleEventConsumer(
    private val connectionFactory: ConnectionFactory,
    private val topology: RabbitMemoryRunLifecycleEventTopology,
) : MemoryRunLifecycleEventConsumer, SmartLifecycle {
    private val log = KLoggers.logger(this)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val channel = Channel<MemoryRunLifecycleEventDelivery>(Channel.UNLIMITED)
    private var listenerContainer: SimpleMessageListenerContainer? = null

    @Volatile
    private var running = false

    override val deliveries: Flow<MemoryRunLifecycleEventDelivery> = channel.receiveAsFlow()

    override fun start() {
        if (running) return
        topology.declare()
        listenerContainer = SimpleMessageListenerContainer(connectionFactory).apply {
            setQueueNames(topology.queueName)
            setPrefetchCount(8)
            acknowledgeMode = AcknowledgeMode.MANUAL
            setMessageListener(ChannelAwareMessageListener { message, rabbitChannel ->
                val deliveryTag = message.messageProperties.deliveryTag
                val delivery = try {
                    RabbitDelivery(json.decodeFromString(String(message.body, Charsets.UTF_8)))
                } catch (error: Throwable) {
                    log.error(error) { "Rejected invalid memory lifecycle event: ${error.message}" }
                    rabbitChannel.basicNack(deliveryTag, false, false)
                    return@ChannelAwareMessageListener
                }
                try {
                    when (runBlocking { channel.send(delivery); delivery.awaitDecision() }) {
                        DeliveryDecision.ACK -> rabbitChannel.basicAck(deliveryTag, false)
                        DeliveryDecision.REDELIVER -> rabbitChannel.basicNack(deliveryTag, false, true)
                        DeliveryDecision.REJECT -> rabbitChannel.basicNack(deliveryTag, false, false)
                    }
                    delivery.completeSettlement()
                } catch (error: Throwable) {
                    delivery.failSettlement(error)
                    log.error(error) { "Memory lifecycle event settlement failed: ${error.message}" }
                    runCatching { rabbitChannel.basicNack(deliveryTag, false, true) }
                }
            })
            start()
        }
        running = true
    }

    override fun stop() {
        listenerContainer?.stop()
        listenerContainer = null
        running = false
    }

    override fun isRunning(): Boolean = running
    override fun isAutoStartup(): Boolean = true
    override fun getPhase(): Int = 200

    private class RabbitDelivery(
        override val event: MemoryRunLifecycleEvent,
    ) : MemoryRunLifecycleEventDelivery {
        private val decision = CompletableDeferred<DeliveryDecision>()
        private val settlement = CompletableDeferred<Unit>()

        override suspend fun acknowledge() = settle(DeliveryDecision.ACK)
        override suspend fun redeliver() = settle(DeliveryDecision.REDELIVER)
        override suspend fun reject() = settle(DeliveryDecision.REJECT)

        suspend fun awaitDecision(): DeliveryDecision = decision.await()
        fun completeSettlement() = settlement.complete(Unit)
        fun failSettlement(error: Throwable) = settlement.completeExceptionally(error)

        private suspend fun settle(value: DeliveryDecision) {
            check(decision.complete(value)) { "Memory lifecycle event was already settled: ${event.runId.value}" }
            settlement.await()
        }
    }

    private enum class DeliveryDecision { ACK, REDELIVER, REJECT }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.rabbit.enabled"], havingValue = "true")
internal class RabbitMemoryRunLifecycleEventTopology(
    private val connectionFactory: ConnectionFactory,
    @Value("\${gromozeka.runtime.rabbit.memory-events-exchange:gromozeka.memory.events}")
    val exchangeName: String,
    @Value("\${gromozeka.runtime.rabbit.memory-events-queue:gromozeka.memory.events.server}")
    val queueName: String,
    @Value("\${gromozeka.runtime.rabbit.memory-events-routing-key:memory-run}")
    val routingKey: String,
) {
    private val log = KLoggers.logger(this)
    private val declared = AtomicBoolean(false)

    @Synchronized
    fun declare() {
        if (declared.get()) return
        repeat(TOPOLOGY_DECLARE_ATTEMPTS) { attempt ->
            try {
                val admin = RabbitAdmin(connectionFactory)
                val exchange = DirectExchange(exchangeName, true, false)
                val queue = QueueBuilder.durable(queueName).build()
                admin.declareExchange(exchange)
                admin.declareQueue(queue)
                admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(routingKey))
                declared.set(true)
                return
            } catch (error: Throwable) {
                if (attempt == TOPOLOGY_DECLARE_ATTEMPTS - 1) throw error
                log.warn(error) {
                    "Rabbit memory event topology is not ready: " +
                        "attempt=${attempt + 1}/$TOPOLOGY_DECLARE_ATTEMPTS error=${error.message}"
                }
                Thread.sleep(TOPOLOGY_DECLARE_RETRY_MILLIS)
            }
        }
    }

    private companion object {
        const val TOPOLOGY_DECLARE_ATTEMPTS = 30
        const val TOPOLOGY_DECLARE_RETRY_MILLIS = 1_000L
    }
}

private fun RabbitTemplate.requireMemoryEventPublisherConfirms() {
    val factory = connectionFactory
    check(factory.isPublisherConfirms || factory.isSimplePublisherConfirms) {
        "Rabbit memory lifecycle event delivery requires publisher confirms"
    }
}

private fun RabbitTemplate.publishMemoryEventConfirmed(publish: (RabbitOperations) -> Unit) {
    invoke<Unit> { operations ->
        publish(operations)
        operations.waitForConfirmsOrDie(10_000L)
    }
}
