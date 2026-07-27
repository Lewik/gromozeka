package com.gromozeka.infrastructure.runtime

import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.WorkerControlClient
import com.gromozeka.domain.service.WorkerControlHandler
import com.gromozeka.domain.service.WorkerControlRequest
import com.gromozeka.domain.service.WorkerControlResult
import com.gromozeka.shared.utils.sha256
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.amqp.core.AcknowledgeMode
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageBuilder
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicBoolean

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.rabbit.enabled"], havingValue = "true")
class RabbitWorkerControlClient(
    connectionFactory: ConnectionFactory,
    private val topology: RabbitWorkerControlTopology,
    @Value("\${gromozeka.runtime.worker-control.timeout-millis:120000}")
    private val timeoutMillis: Long,
) : WorkerControlClient {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
    private val rabbitTemplate = RabbitTemplate(connectionFactory).apply {
        setReplyTimeout(timeoutMillis)
    }

    init {
        require(timeoutMillis > 0) { "Worker control timeout must be positive" }
    }

    override suspend fun execute(request: WorkerControlRequest): WorkerControlResult =
        withContext(Dispatchers.IO) {
            topology.declareExchange()
            val response = rabbitTemplate.convertSendAndReceive(
                topology.exchangeName,
                topology.routingKey(request.target),
                json.encodeToString(request),
            ) as? String
                ?: error(
                    "Worker control request timed out after ${timeoutMillis}ms; " +
                        "the outcome is unknown and Gromozeka will not retry it automatically: ${request.id.value}"
                )
            json.decodeFromString(response)
        }
}

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.rabbit.enabled", "gromozeka.runtime.worker.enabled"],
    havingValue = "true",
)
class RabbitWorkerControlConsumer(
    private val connectionFactory: ConnectionFactory,
    private val rabbitTemplate: RabbitTemplate,
    private val topology: RabbitWorkerControlTopology,
    private val workerIdentity: ConversationRuntimeWorkerIdentity,
    private val handler: WorkerControlHandler,
) : SmartLifecycle {
    private val log = KLoggers.logger(this)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
    private var listenerContainer: SimpleMessageListenerContainer? = null

    @Volatile
    private var running = false

    override fun start() {
        if (running) {
            return
        }
        val queueName = topology.declareWorkerQueue(workerIdentity)
        listenerContainer = SimpleMessageListenerContainer(connectionFactory).apply {
            setQueueNames(queueName)
            setPrefetchCount(1)
            acknowledgeMode = AcknowledgeMode.MANUAL
            setMessageListener(ChannelAwareMessageListener { message, channel ->
                val deliveryTag = message.messageProperties.deliveryTag
                val request = runCatching {
                    json.decodeFromString<WorkerControlRequest>(String(message.body, Charsets.UTF_8))
                }.getOrElse { error ->
                    log.error(error) { "Rejected invalid worker control request: ${error.message}" }
                    channel.basicNack(deliveryTag, false, false)
                    return@ChannelAwareMessageListener
                }
                if (request.target != workerIdentity) {
                    log.error {
                        "Rejected worker control request for another session: " +
                            "expected=$workerIdentity actual=${request.target}"
                    }
                    channel.basicNack(deliveryTag, false, false)
                    return@ChannelAwareMessageListener
                }

                channel.basicAck(deliveryTag, false)
                val result = runBlocking {
                    runCatching { handler.handle(request) }
                        .getOrElse { error ->
                            WorkerControlResult(
                                requestId = request.id,
                                status = WorkerControlResult.Status.FAILED,
                                errorCode = error::class.simpleName ?: "WorkerControlFailure",
                                errorMessage = error.message ?: "Worker control operation failed",
                            )
                        }
                }
                sendReply(message, result)
            })
            start()
        }
        running = true
        log.info { "Rabbit worker control consumer started: identity=$workerIdentity queue=$queueName" }
    }

    override fun stop() {
        listenerContainer?.stop()
        listenerContainer = null
        topology.deleteWorkerQueue(workerIdentity)
        running = false
    }

    override fun isRunning(): Boolean = running

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = 250

    private fun sendReply(
        requestMessage: Message,
        result: WorkerControlResult,
    ) {
        val replyTo = requestMessage.messageProperties.replyTo
        if (replyTo.isNullOrBlank()) {
            log.warn { "Worker control request has no reply queue: ${result.requestId.value}" }
            return
        }
        val response = MessageBuilder
            .withBody(json.encodeToString(result).toByteArray(Charsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
            .setCorrelationId(requestMessage.messageProperties.correlationId)
            .build()
        runCatching {
            rabbitTemplate.send("", replyTo, response)
        }.onFailure { error ->
            log.error(error) {
                "Worker control operation completed but its response could not be delivered: " +
                    "request=${result.requestId.value} status=${result.status}"
            }
        }
    }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.rabbit.enabled"], havingValue = "true")
class RabbitWorkerControlTopology(
    private val connectionFactory: ConnectionFactory,
    @Value("\${gromozeka.runtime.worker-control.exchange:gromozeka.worker.control}")
    val exchangeName: String,
    @Value("\${gromozeka.runtime.worker-control.queue-prefix:gromozeka.worker.control}")
    private val queuePrefix: String,
) {
    private val exchangeDeclared = AtomicBoolean(false)

    fun routingKey(identity: ConversationRuntimeWorkerIdentity): String =
        "worker.${identity.workerId.value.sha256().take(32)}.session.${identity.sessionId.value.sha256().take(32)}"

    fun declareWorkerQueue(identity: ConversationRuntimeWorkerIdentity): String {
        declareExchange()
        val queueName = "$queuePrefix.${routingKey(identity)}"
        val admin = RabbitAdmin(connectionFactory)
        val queue = QueueBuilder.nonDurable(queueName)
            .exclusive()
            .autoDelete()
            .build()
        admin.declareQueue(queue)
        admin.declareBinding(
            BindingBuilder.bind(queue)
                .to(DirectExchange(exchangeName, true, false))
                .with(routingKey(identity))
        )
        return queueName
    }

    fun deleteWorkerQueue(identity: ConversationRuntimeWorkerIdentity) {
        RabbitAdmin(connectionFactory).deleteQueue("$queuePrefix.${routingKey(identity)}")
    }

    @Synchronized
    fun declareExchange() {
        if (exchangeDeclared.compareAndSet(false, true)) {
            runCatching {
                RabbitAdmin(connectionFactory).declareExchange(DirectExchange(exchangeName, true, false))
            }.onFailure {
                exchangeDeclared.set(false)
                throw it
            }
        }
    }
}
