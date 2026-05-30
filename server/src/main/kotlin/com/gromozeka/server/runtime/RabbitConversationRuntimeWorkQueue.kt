package com.gromozeka.server.runtime

import com.gromozeka.domain.service.ConversationRuntimeWorkDelivery
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkQueue
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
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
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Service
@Primary
@ConditionalOnProperty(name = ["gromozeka.runtime.rabbit.enabled"], havingValue = "true")
class RabbitConversationRuntimeWorkQueue(
    private val connectionFactory: ConnectionFactory,
    private val rabbitTemplate: RabbitTemplate,
    @Value("\${gromozeka.runtime.rabbit.exchange:gromozeka.runtime.work}") private val exchangeName: String,
    @Value("\${gromozeka.runtime.rabbit.queue:gromozeka.runtime.work}") private val queueName: String,
    @Value("\${gromozeka.runtime.rabbit.shards:16}") private val shardCount: Int,
    @Value("\${gromozeka.runtime.rabbit.dead-letter-exchange:gromozeka.runtime.work.dlx}")
    private val deadLetterExchangeName: String,
    @Value("\${gromozeka.runtime.rabbit.max-retries:8}") private val maxRetries: Int,
    private val runtimeWorkerDescriptor: ConversationRuntimeWorkerDescriptor,
) : ConversationRuntimeWorkQueue, SmartLifecycle {
    private val log = KLoggers.logger(this)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
    private val channel = Channel<ConversationRuntimeWorkDelivery>(Channel.UNLIMITED)
    private val consumerRoutes = runtimeWorkerDescriptor.consumerRoutes()
    private val exchangeDeclared = AtomicBoolean(false)
    private val declaredRoutes = ConcurrentHashMap.newKeySet<String>()
    private var listenerContainer: SimpleMessageListenerContainer? = null
    @Volatile
    private var running = false

    init {
        require(shardCount > 0) { "Rabbit runtime shard count must be positive" }
        require(maxRetries >= 0) { "Rabbit runtime max retries must not be negative" }
    }

    override val deliveries: Flow<ConversationRuntimeWorkDelivery> = channel.receiveAsFlow()

    override suspend fun submit(item: ConversationRuntimeWorkItem) {
        val route = RuntimeWorkRoute.from(item.requirements)
        declareRouteQueues(route)
        rabbitTemplate.convertAndSend(exchangeName, routingKey(route, item.conversationId.value), json.encodeToString(item))
    }

    override fun start() {
        if (running) {
            return
        }

        consumerRoutes.forEach(::declareRouteQueues)
        val listenerQueueNames = consumerRoutes
            .flatMap { route -> route.queueNames() }
            .distinct()
        val consumerCount = shardCount.coerceAtMost(listenerQueueNames.size).coerceAtLeast(1)
        listenerContainer = SimpleMessageListenerContainer(connectionFactory).apply {
            setQueueNames(*listenerQueueNames.toTypedArray())
            setConcurrentConsumers(consumerCount)
            setMaxConcurrentConsumers(consumerCount)
            setPrefetchCount(1)
            acknowledgeMode = AcknowledgeMode.MANUAL
            setMessageListener(ChannelAwareMessageListener { message, rabbitChannel ->
                val deliveryTag = message.messageProperties.deliveryTag
                try {
                    val delivery = RabbitWorkDelivery(
                        item = decode(message),
                        retryCount = message.retryCount(),
                        maxRetries = maxRetries,
                    )
                    when (runBlocking {
                        channel.send(delivery)
                        delivery.awaitDecision()
                    }) {
                        DeliveryDecision.ACK -> rabbitChannel.basicAck(deliveryTag, false)
                        DeliveryDecision.RETRY -> retryOrDeadLetter(message, rabbitChannel, deliveryTag)
                        DeliveryDecision.FAIL -> rabbitChannel.basicNack(deliveryTag, false, false)
                    }
                } catch (error: Throwable) {
                    log.error(error) { "Failed to consume runtime work item: ${error.message}" }
                    rabbitChannel.basicNack(deliveryTag, false, false)
                }
            })
            start()
        }
        running = true
        log.info {
            "Rabbit runtime work queue started: exchange=$exchangeName queues=${listenerQueueNames.size} " +
                "routes=${consumerRoutes.size} consumers=$consumerCount " +
                "workerCapabilities=${runtimeWorkerDescriptor.capabilities.joinToString()} " +
                "workerAffinities=${runtimeWorkerDescriptor.affinities.joinToString()}"
        }
    }

    override fun stop() {
        listenerContainer?.stop()
        listenerContainer = null
        running = false
    }

    override fun isRunning(): Boolean = running

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = 0

    @Synchronized
    private fun declareRouteQueues(route: RuntimeWorkRoute) {
        declareExchanges()
        if (!declaredRoutes.add(route.id)) {
            return
        }
        repeat(RABBIT_TOPOLOGY_DECLARE_ATTEMPTS) { attempt ->
            try {
                val admin = RabbitAdmin(connectionFactory)
                route.queueNames().forEachIndexed { shardIndex, shardQueueName ->
                    val routingKey = route.routingKey(shardIndex)
                    val deadLetterQueueName = "$shardQueueName.dlq"
                    val queue = QueueBuilder.durable(shardQueueName)
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
                }
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

    private fun routingKey(route: RuntimeWorkRoute, conversationId: String): String =
        route.routingKey(Math.floorMod(conversationId.hashCode(), shardCount))

    private fun decode(message: Message): ConversationRuntimeWorkItem =
        json.decodeFromString(String(message.body, Charsets.UTF_8))

    private fun retryOrDeadLetter(
        message: Message,
        rabbitChannel: com.rabbitmq.client.Channel,
        deliveryTag: Long,
    ) {
        val routingKey = message.messageProperties.receivedRoutingKey
        if (routingKey.isNullOrBlank()) {
            rabbitChannel.basicNack(deliveryTag, false, false)
            return
        }

        val retryCount = message.retryCount()
        if (retryCount >= maxRetries) {
            rabbitChannel.basicNack(deliveryTag, false, false)
            return
        }

        val retryMessage = MessageBuilder.withBody(message.body)
            .copyProperties(message.messageProperties)
            .setHeader(RETRY_COUNT_HEADER, retryCount + 1)
            .build()
        rabbitTemplate.send(exchangeName, routingKey, retryMessage)
        rabbitChannel.basicAck(deliveryTag, false)
    }

    private fun Message.retryCount(): Int =
        when (val raw = messageProperties.headers[RETRY_COUNT_HEADER]) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: 0
            else -> 0
        }

    private fun RuntimeWorkRoute.queueNames(): List<String> =
        List(shardCount) { shardIndex -> "$queueName.${id}.$shardIndex" }

    private fun RuntimeWorkRoute.routingKey(shardIndex: Int): String = "${id}.shard-$shardIndex"

    private fun ConversationRuntimeWorkerDescriptor.consumerRoutes(): List<RuntimeWorkRoute> {
        val affinityOptions = listOf<ConversationRuntimeWorkerAffinity?>(null) + affinities.sortedWith(
            compareBy<ConversationRuntimeWorkerAffinity> { it.kind.name }.thenBy { it.value }
        )
        return capabilities.consumerLanes()
            .flatMap { lane ->
                affinityOptions.map { affinity -> RuntimeWorkRoute(lane = lane, affinity = affinity) }
            }
            .distinctBy { it.id }
    }

    private data class RuntimeWorkRoute(
        val lane: RuntimeWorkLane,
        val affinity: ConversationRuntimeWorkerAffinity?,
    ) {
        val id: String = buildString {
            append("lane-")
            append(lane.name.lowercase().replace('_', '-'))
            append(".aff-")
            append(affinity?.let(::affinityRouteId) ?: "global")
        }

        companion object {
            fun from(requirements: ConversationRuntimeTaskRequirements): RuntimeWorkRoute =
                RuntimeWorkRoute(
                    lane = routeLane(requirements.capabilities),
                    affinity = requirements.affinity,
                )

            private fun routeLane(capabilities: Set<ConversationRuntimeWorkerCapability>): RuntimeWorkLane =
                when {
                    ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL in capabilities -> RuntimeWorkLane.LOCAL_AGENT
                    ConversationRuntimeWorkerCapability.TOOL_EXECUTION in capabilities -> RuntimeWorkLane.TOOL
                    ConversationRuntimeWorkerCapability.LLM_RUNTIME in capabilities &&
                        ConversationRuntimeWorkerCapability.CONVERSATION_TURN !in capabilities -> RuntimeWorkLane.LLM
                    else -> RuntimeWorkLane.CONVERSATION
                }

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

    private enum class RuntimeWorkLane {
        CONVERSATION,
        LLM,
        TOOL,
        LOCAL_AGENT,
    }

    private fun Set<ConversationRuntimeWorkerCapability>.consumerLanes(): Set<RuntimeWorkLane> =
        buildSet {
            if (ConversationRuntimeWorkerCapability.CONVERSATION_TURN in this@consumerLanes) {
                add(RuntimeWorkLane.CONVERSATION)
            }
            if (ConversationRuntimeWorkerCapability.LLM_RUNTIME in this@consumerLanes) {
                add(RuntimeWorkLane.LLM)
            }
            if (ConversationRuntimeWorkerCapability.TOOL_EXECUTION in this@consumerLanes) {
                add(RuntimeWorkLane.TOOL)
            }
            if (ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL in this@consumerLanes) {
                add(RuntimeWorkLane.LOCAL_AGENT)
            }
        }

    private class RabbitWorkDelivery(
        override val item: ConversationRuntimeWorkItem,
        override val retryCount: Int,
        private val maxRetries: Int,
    ) : ConversationRuntimeWorkDelivery {
        override val isFinalRetry: Boolean = retryCount >= maxRetries

        private val decision = CompletableDeferred<DeliveryDecision>()

        override suspend fun ack() {
            decision.complete(DeliveryDecision.ACK)
        }

        override suspend fun retry() {
            decision.complete(DeliveryDecision.RETRY)
        }

        override suspend fun fail() {
            decision.complete(DeliveryDecision.FAIL)
        }

        suspend fun awaitDecision(): DeliveryDecision = decision.await()
    }

    private enum class DeliveryDecision {
        ACK,
        RETRY,
        FAIL,
    }

    private companion object {
        const val RABBIT_TOPOLOGY_DECLARE_ATTEMPTS = 30
        const val RABBIT_TOPOLOGY_DECLARE_RETRY_MILLIS = 1_000L
        const val RETRY_COUNT_HEADER = "x-gromozeka-runtime-retry-count"
    }
}
