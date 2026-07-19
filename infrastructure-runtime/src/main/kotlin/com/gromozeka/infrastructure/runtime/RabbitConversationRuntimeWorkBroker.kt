package com.gromozeka.infrastructure.runtime

import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeWorkConsumer
import com.gromozeka.domain.service.ConversationRuntimeWorkDelivery
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkPublisher
import com.gromozeka.domain.service.ConversationRuntimeWorkerAffinity
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
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
import org.springframework.amqp.core.MessageBuilder
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
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.rabbit.enabled"], havingValue = "true")
internal class RabbitConversationRuntimeWorkPublisher(
    private val rabbitTemplate: RabbitTemplate,
    private val topology: RabbitConversationRuntimeWorkTopology,
) : ConversationRuntimeWorkPublisher {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    init {
        rabbitTemplate.requirePublisherConfirms()
    }

    override suspend fun submit(item: ConversationRuntimeWorkItem) {
        val route = RabbitRuntimeWorkRoute.from(item.requirements)
        topology.declareRoute(route)
        rabbitTemplate.publishConfirmed { operations ->
            operations.convertAndSend(
                topology.exchangeName,
                topology.routingKey(route),
                json.encodeToString(item),
            )
        }
    }
}

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.rabbit.enabled", "gromozeka.runtime.worker.enabled"],
    havingValue = "true",
)
internal class RabbitConversationRuntimeWorkConsumer(
    private val connectionFactory: ConnectionFactory,
    private val rabbitTemplate: RabbitTemplate,
    private val topology: RabbitConversationRuntimeWorkTopology,
    @Value("\${gromozeka.runtime.rabbit.max-redeliveries:8}") private val maxRedeliveries: Int,
    runtimeWorkerDescriptor: ConversationRuntimeWorkerDescriptor,
) : ConversationRuntimeWorkConsumer, SmartLifecycle {
    private val log = KLoggers.logger(this)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
    private val channel = Channel<ConversationRuntimeWorkDelivery>(Channel.UNLIMITED)
    private val consumerRoutes = runtimeWorkerDescriptor.consumerRoutes()
    private val workerCapabilities = runtimeWorkerDescriptor.capabilities
    private val workerAffinities = runtimeWorkerDescriptor.effectiveAffinities()
    private var listenerContainer: SimpleMessageListenerContainer? = null

    @Volatile
    private var running = false

    init {
        require(maxRedeliveries >= 0) { "Rabbit runtime max redeliveries must not be negative" }
        rabbitTemplate.requirePublisherConfirms()
    }

    override val deliveries: Flow<ConversationRuntimeWorkDelivery> = channel.receiveAsFlow()

    override fun start() {
        if (running) {
            return
        }

        consumerRoutes.forEach(topology::declareRoute)
        val listenerQueueNames = consumerRoutes
            .map(topology::queueName)
            .distinct()
        check(listenerQueueNames.isNotEmpty()) {
            "Conversation runtime worker has no Rabbit routes: capabilities=$workerCapabilities affinities=$workerAffinities"
        }
        listenerContainer = SimpleMessageListenerContainer(connectionFactory).apply {
            setQueueNames(*listenerQueueNames.toTypedArray())
            setPrefetchCount(1)
            acknowledgeMode = AcknowledgeMode.MANUAL
            setMessageListener(ChannelAwareMessageListener { message, rabbitChannel ->
                val deliveryTag = message.messageProperties.deliveryTag
                val delivery = try {
                    RabbitWorkDelivery(
                        item = decode(message),
                        redeliveryCount = message.redeliveryCount(),
                        maxRedeliveries = maxRedeliveries,
                    )
                } catch (error: Throwable) {
                    log.error(error) { "Rejected invalid runtime work item: ${error.message}" }
                    rabbitChannel.basicNack(deliveryTag, false, false)
                    return@ChannelAwareMessageListener
                }
                try {
                    val decision = runBlocking {
                        channel.send(delivery)
                        delivery.awaitDecision()
                    }
                    when (decision) {
                        DeliveryDecision.ACK -> rabbitChannel.basicAck(deliveryTag, false)
                        DeliveryDecision.REDELIVER -> redeliverOrDeadLetter(message, rabbitChannel, deliveryTag)
                        DeliveryDecision.REJECT -> rabbitChannel.basicNack(deliveryTag, false, false)
                    }
                    delivery.completeSettlement()
                } catch (error: Throwable) {
                    delivery.failSettlement(error)
                    log.error(error) {
                        "Runtime work delivery infrastructure failed; original delivery will be requeued: ${error.message}"
                    }
                    runCatching {
                        rabbitChannel.basicNack(deliveryTag, false, true)
                    }.onFailure { nackError ->
                        log.error(nackError) {
                            "Failed to requeue runtime work delivery after settlement failure: ${nackError.message}"
                        }
                    }
                }
            })
            start()
        }
        running = true
        log.info {
            "Rabbit runtime work consumer started: exchange=${topology.exchangeName} " +
                "queues=${listenerQueueNames.size} routes=${consumerRoutes.size} " +
                "workerCapabilities=${workerCapabilities.joinToString()} workerAffinities=${workerAffinities.joinToString()}"
        }
    }

    override fun stop() {
        listenerContainer?.stop()
        listenerContainer = null
        running = false
    }

    override fun isRunning(): Boolean = running

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = 200

    private fun decode(message: Message): ConversationRuntimeWorkItem =
        json.decodeFromString(String(message.body, Charsets.UTF_8))

    private fun redeliverOrDeadLetter(
        message: Message,
        rabbitChannel: com.rabbitmq.client.Channel,
        deliveryTag: Long,
    ) {
        val routingKey = message.messageProperties.receivedRoutingKey
        if (routingKey.isNullOrBlank()) {
            rabbitChannel.basicNack(deliveryTag, false, false)
            return
        }

        val redeliveryCount = message.redeliveryCount()
        if (redeliveryCount >= maxRedeliveries) {
            rabbitChannel.basicNack(deliveryTag, false, false)
            return
        }

        val retryMessage = MessageBuilder.withBody(message.body)
            .copyProperties(message.messageProperties)
            .setHeader(REDELIVERY_COUNT_HEADER, redeliveryCount + 1)
            .build()
        rabbitTemplate.publishConfirmed { operations ->
            operations.send(topology.exchangeName, routingKey, retryMessage)
        }
        rabbitChannel.basicAck(deliveryTag, false)
    }

    private fun Message.redeliveryCount(): Int =
        when (val raw = messageProperties.headers[REDELIVERY_COUNT_HEADER]) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: 0
            else -> 0
        }

    private class RabbitWorkDelivery(
        override val item: ConversationRuntimeWorkItem,
        override val redeliveryCount: Int,
        private val maxRedeliveries: Int,
    ) : ConversationRuntimeWorkDelivery {
        override val isFinalRedelivery: Boolean = redeliveryCount >= maxRedeliveries

        private val decision = CompletableDeferred<DeliveryDecision>()
        private val settlement = CompletableDeferred<Unit>()

        override suspend fun acknowledge() {
            requestSettlement(DeliveryDecision.ACK)
        }

        override suspend fun redeliver() {
            requestSettlement(DeliveryDecision.REDELIVER)
        }

        override suspend fun reject() {
            requestSettlement(DeliveryDecision.REJECT)
        }

        suspend fun awaitDecision(): DeliveryDecision = decision.await()

        fun completeSettlement() {
            settlement.complete(Unit)
        }

        fun failSettlement(error: Throwable) {
            settlement.completeExceptionally(error)
        }

        private suspend fun requestSettlement(requestedDecision: DeliveryDecision) {
            check(decision.complete(requestedDecision)) {
                "Rabbit runtime work delivery was already settled: task=${item.taskId.value}"
            }
            settlement.await()
        }
    }

    private enum class DeliveryDecision {
        ACK,
        REDELIVER,
        REJECT,
    }

    private companion object {
        const val REDELIVERY_COUNT_HEADER = "x-gromozeka-runtime-redelivery-count"
    }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.rabbit.enabled"], havingValue = "true")
internal class RabbitConversationRuntimeWorkTopology(
    private val connectionFactory: ConnectionFactory,
    @Value("\${gromozeka.runtime.rabbit.exchange:gromozeka.runtime.work}") val exchangeName: String,
    @Value("\${gromozeka.runtime.rabbit.queue:gromozeka.runtime.work}") private val queueNamePrefix: String,
    @Value("\${gromozeka.runtime.rabbit.dead-letter-exchange:gromozeka.runtime.work.dlx}")
    private val deadLetterExchangeName: String,
) {
    private val log = KLoggers.logger(this)
    private val exchangeDeclared = AtomicBoolean(false)
    private val declaredRoutes = ConcurrentHashMap.newKeySet<String>()

    fun routingKey(route: RabbitRuntimeWorkRoute): String = route.id

    fun queueName(route: RabbitRuntimeWorkRoute): String = "$queueNamePrefix.${route.id}"

    @Synchronized
    fun declareRoute(route: RabbitRuntimeWorkRoute) {
        declareExchanges()
        if (!declaredRoutes.add(route.id)) {
            return
        }
        repeat(RABBIT_TOPOLOGY_DECLARE_ATTEMPTS) { attempt ->
            try {
                val admin = RabbitAdmin(connectionFactory)
                val routeQueueName = queueName(route)
                val routingKey = routingKey(route)
                val deadLetterQueueName = "$routeQueueName.dlq"
                val queue = QueueBuilder.durable(routeQueueName)
                    .withArgument("x-dead-letter-exchange", deadLetterExchangeName)
                    .withArgument("x-dead-letter-routing-key", routingKey)
                    .build()
                val deadLetterQueue = QueueBuilder.durable(deadLetterQueueName).build()
                admin.declareQueue(queue)
                admin.declareQueue(deadLetterQueue)
                admin.declareBinding(
                    BindingBuilder.bind(queue)
                        .to(DirectExchange(exchangeName, true, false))
                        .with(routingKey)
                )
                admin.declareBinding(
                    BindingBuilder.bind(deadLetterQueue)
                        .to(DirectExchange(deadLetterExchangeName, true, false))
                        .with(routingKey)
                )
                return
            } catch (error: Throwable) {
                declaredRoutes.remove(route.id)
                if (attempt == RABBIT_TOPOLOGY_DECLARE_ATTEMPTS - 1) {
                    throw error
                }
                log.warn(error) {
                    "Rabbit runtime work route topology is not ready yet: route=${route.id} " +
                        "attempt=${attempt + 1}/$RABBIT_TOPOLOGY_DECLARE_ATTEMPTS error=${error.message}"
                }
                Thread.sleep(RABBIT_TOPOLOGY_DECLARE_RETRY_MILLIS)
            }
        }
    }

    @Synchronized
    private fun declareExchanges() {
        if (exchangeDeclared.get()) {
            return
        }
        repeat(RABBIT_TOPOLOGY_DECLARE_ATTEMPTS) { attempt ->
            try {
                val admin = RabbitAdmin(connectionFactory)
                admin.declareExchange(DirectExchange(exchangeName, true, false))
                admin.declareExchange(DirectExchange(deadLetterExchangeName, true, false))
                exchangeDeclared.set(true)
                return
            } catch (error: Throwable) {
                if (attempt == RABBIT_TOPOLOGY_DECLARE_ATTEMPTS - 1) {
                    throw error
                }
                log.warn(error) {
                    "Rabbit runtime work exchanges are not ready yet: " +
                        "attempt=${attempt + 1}/$RABBIT_TOPOLOGY_DECLARE_ATTEMPTS error=${error.message}"
                }
                Thread.sleep(RABBIT_TOPOLOGY_DECLARE_RETRY_MILLIS)
            }
        }
    }

    private companion object {
        const val RABBIT_TOPOLOGY_DECLARE_ATTEMPTS = 30
        const val RABBIT_TOPOLOGY_DECLARE_RETRY_MILLIS = 1_000L
    }
}

internal data class RabbitRuntimeWorkRoute(
    val lane: RabbitRuntimeWorkLane,
    val affinity: ConversationRuntimeWorkerAffinity?,
) {
    val id: String = buildString {
        append("lane-")
        append(lane.name.lowercase().replace('_', '-'))
        append(".aff-")
        append(affinity?.let(::affinityRouteId) ?: "global")
    }

    companion object {
        fun from(requirements: ConversationRuntimeTaskRequirements): RabbitRuntimeWorkRoute =
            RabbitRuntimeWorkRoute(
                lane = RabbitRuntimeWorkLane.from(requirements.capabilities),
                affinity = requirements.affinity,
            )

        private fun affinityRouteId(affinity: ConversationRuntimeWorkerAffinity): String =
            "${affinity.kind.name.lowercase()}-" +
                "${Integer.toUnsignedString(affinity.value.hashCode(), 36)}-${affinity.value.routeToken()}"

        private fun String.routeToken(): String =
            lowercase()
                .map { char -> if (char.isLetterOrDigit()) char else '-' }
                .joinToString("")
                .trim('-')
                .take(48)
                .ifBlank { "value" }
    }
}

internal enum class RabbitRuntimeWorkLane(
    val requiredCapabilities: Set<ConversationRuntimeWorkerCapability>,
) {
    CONVERSATION(
        setOf(
            ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
            ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
        )
    ),
    LLM(
        setOf(
            ConversationRuntimeWorkerCapability.LLM_RUNTIME,
            ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
        )
    ),
    TOOL(setOf(ConversationRuntimeWorkerCapability.TOOL_EXECUTION)),
    LOCAL_AGENT(
        setOf(
            ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
            ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
        )
    ),
    MEMORY(setOf(ConversationRuntimeWorkerCapability.MEMORY_PIPELINE));

    companion object {
        fun from(capabilities: Set<ConversationRuntimeWorkerCapability>): RabbitRuntimeWorkLane =
            entries.singleOrNull { it.requiredCapabilities == capabilities }
                ?: error(
                    "Unsupported Rabbit runtime capability profile: " +
                        capabilities.sortedBy { it.name }.joinToString()
                )
    }
}

internal fun ConversationRuntimeWorkerDescriptor.effectiveAffinities(): Set<ConversationRuntimeWorkerAffinity> =
    affinities + ConversationRuntimeWorkerAffinity(
        kind = ConversationRuntimeWorkerAffinity.Kind.WORKER,
        value = id.value,
    )

internal fun ConversationRuntimeWorkerDescriptor.consumerRoutes(): List<RabbitRuntimeWorkRoute> {
    val affinityOptions = listOf<ConversationRuntimeWorkerAffinity?>(null) + effectiveAffinities().sortedWith(
        compareBy<ConversationRuntimeWorkerAffinity> { it.kind.name }.thenBy { it.value }
    )
    return capabilities.consumerLanes()
        .flatMap { lane ->
            affinityOptions.map { affinity -> RabbitRuntimeWorkRoute(lane = lane, affinity = affinity) }
        }
        .distinctBy { it.id }
}

private fun Set<ConversationRuntimeWorkerCapability>.consumerLanes(): Set<RabbitRuntimeWorkLane> =
    RabbitRuntimeWorkLane.entries
        .filterTo(mutableSetOf()) { containsAll(it.requiredCapabilities) }

private fun RabbitTemplate.requirePublisherConfirms() {
    val factory = connectionFactory
    check(factory.isPublisherConfirms || factory.isSimplePublisherConfirms) {
        "Rabbit runtime work delivery requires publisher confirms"
    }
}

private fun RabbitTemplate.publishConfirmed(
    publish: (RabbitOperations) -> Unit,
) {
    invoke<Unit> { operations ->
        publish(operations)
        operations.waitForConfirmsOrDie(RABBIT_PUBLISH_CONFIRM_TIMEOUT_MILLIS)
    }
}

private const val RABBIT_PUBLISH_CONFIRM_TIMEOUT_MILLIS = 10_000L
