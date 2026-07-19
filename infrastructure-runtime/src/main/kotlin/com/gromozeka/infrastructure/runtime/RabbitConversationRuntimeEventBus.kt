package com.gromozeka.infrastructure.runtime

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventBus
import com.gromozeka.domain.service.ConversationRuntimeEventSubscription
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.MessageListener
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicBoolean

@Service
@Primary
@ConditionalOnProperty(name = ["gromozeka.runtime.rabbit.enabled"], havingValue = "true")
class RabbitConversationRuntimeEventBus(
    private val connectionFactory: ConnectionFactory,
    private val rabbitTemplate: RabbitTemplate,
    @Value("\${gromozeka.runtime.rabbit.events-exchange:gromozeka.runtime.events}") private val exchangeName: String,
    @Value("\${gromozeka.runtime.rabbit.events-queue-prefix:gromozeka.runtime.events}") private val queuePrefix: String,
) : ConversationRuntimeEventBus {
    private val log = KLoggers.logger(this)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
    private val exchangeDeclared = AtomicBoolean(false)

    override suspend fun subscribe(conversationId: Conversation.Id): ConversationRuntimeEventSubscription {
        val admin = RabbitAdmin(connectionFactory)
        val queueName = "$queuePrefix.${conversationId.value}.${uuid7()}"
        val queue = QueueBuilder.nonDurable(queueName).exclusive().autoDelete().build()
        val binding = BindingBuilder.bind(queue).to(DirectExchange(exchangeName, true, false)).with(conversationId.value)
        val channel = Channel<ConversationRuntimeEvent>(Channel.UNLIMITED)

        declareExchange()
        admin.declareQueue(queue)
        admin.declareBinding(binding)

        val container = SimpleMessageListenerContainer(connectionFactory).apply {
            setQueueNames(queueName)
            setMessageListener(MessageListener { message ->
                runCatching {
                    val payload = String(message.body, Charsets.UTF_8)
                    channel.trySend(json.decodeFromString<ConversationRuntimeEvent>(payload))
                }.onFailure { error ->
                    log.error(error) {
                        "Failed to consume runtime event: conversation=${conversationId.value} error=${error.message}"
                    }
                    throw error
                }
            })
            start()
        }

        return object : ConversationRuntimeEventSubscription {
            override val events: Flow<ConversationRuntimeEvent> = channel.receiveAsFlow()

            override suspend fun close() {
                container.stop()
                admin.deleteQueue(queueName)
                channel.close()
            }
        }
    }

    override suspend fun publish(event: ConversationRuntimeEvent) {
        declareExchange()
        rabbitTemplate.convertAndSend(exchangeName, event.conversationId.value, json.encodeToString(event))
    }

    @Synchronized
    private fun declareExchange() {
        if (exchangeDeclared.get()) {
            return
        }

        repeat(RABBIT_TOPOLOGY_DECLARE_ATTEMPTS) { attempt ->
            try {
                RabbitAdmin(connectionFactory).declareExchange(DirectExchange(exchangeName, true, false))
                exchangeDeclared.set(true)
                return
            } catch (error: Throwable) {
                if (attempt == RABBIT_TOPOLOGY_DECLARE_ATTEMPTS - 1) {
                    throw error
                }
                log.warn(error) {
                    "Rabbit runtime event exchange is not ready yet: " +
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
