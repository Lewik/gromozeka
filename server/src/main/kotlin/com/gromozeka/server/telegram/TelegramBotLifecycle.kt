package com.gromozeka.server.telegram

import com.gromozeka.domain.model.TelegramConnection
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.repository.TelegramChannelRepository
import com.gromozeka.domain.repository.TelegramConnectionRepository
import com.gromozeka.domain.service.ConversationDomainService
import klog.KLoggers
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonObject
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.server.enabled"], havingValue = "true", matchIfMissing = true)
class TelegramBotLifecycle(
    private val apiFactory: TelegramConnectionApiFactory,
    private val connections: TelegramConnectionRepository,
    private val repository: TelegramChannelRepository,
    private val gateway: TelegramConversationGateway,
    private val identities: IdentityRepository,
    private val conversations: ConversationDomainService,
) : SmartLifecycle {
    private val log = KLoggers.logger(this)
    private var job: Job? = null

    override fun start() {
        if (!apiFactory.serverEnabled || job?.isActive == true) return
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val workers = mutableMapOf<String, Pair<TelegramConnection, Job>>()
            try {
                while (isActive) {
                    try {
                        val enabled = connections.list().filter { it.enabled }.associateBy { it.id }
                        for ((id, worker) in workers.toMap()) {
                            if (enabled[id] != worker.first || !worker.second.isActive) {
                                worker.second.cancelAndJoin()
                                if (enabled[id] != worker.first) repository.find(id)?.invocations?.filter { !it.completed && it.submitted }?.forEach {
                                    gateway.stop(it)
                                }
                                workers.remove(id)
                            }
                        }
                        for ((id, connection) in enabled) {
                            if (id !in workers) workers[id] = connection to launch { runConnection(connection) }
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { log.warn { "Telegram registry refresh failed (${error::class.simpleName})" } }
                    delay(3000)
                }
            } finally { workers.values.forEach { it.second.cancel() } }
        }
    }

    private suspend fun runConnection(connection: TelegramConnection) {
        try {
            val session = repository.openExclusiveSession(connection.id) ?: return
            try {
                val api = apiFactory.create(connection)
                val bot = api.call("getMe").jsonObject
                check(bot.long("id") == connection.botId && bot.boolean("can_read_all_group_messages"))
                check(api.call("getWebhookInfo").jsonObject.string("url").isNullOrEmpty()) { "Bot has an active webhook" }
                val policy = TelegramInboundPolicy(identities, conversations)
                val processor = TelegramBotProcessor(connection, api, gateway, session, { policy.prepare(connection, it) })
                processor.initialize()
                log.info { "Telegram group adapter started for bot ${connection.botId}" }
                coroutineScope {
                    launch {
                        while (isActive) { processor.typing(); delay(4000) }
                    }
                    while (isActive) {
                        processor.synchronize()
                        try { processor.poll() }
                        catch (error: TelegramApiFailure) {
                            if (error.code != 429) throw error
                            delay((error.retryAfterSeconds ?: 5).coerceIn(1, 60) * 1000)
                        } catch (_: TelegramDeliveryUncertain) { delay(3000) }
                        delay(500)
                    }
                }
            } finally { withContext(NonCancellable) { session.close() } }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            log.error { "Telegram adapter paused for bot ${connection.botId} (${error::class.simpleName}); check configuration and connectivity" }
            delay(10000)
        }
    }

    override fun stop() { job?.cancel() }
    override fun stop(callback: Runnable) {
        val active = job
        if (active == null) callback.run() else { active.invokeOnCompletion { callback.run() }; active.cancel() }
    }
    override fun isRunning(): Boolean = job?.isActive == true
    override fun getPhase(): Int = 400
}
