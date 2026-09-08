package com.gromozeka.server.telegram

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.TelegramBotSession
import com.gromozeka.domain.service.*
import kotlinx.serialization.json.*
import kotlin.time.Clock

class TelegramBotProcessor(
    private val connection: TelegramConnection,
    private val api: TelegramApi,
    private val gateway: TelegramConversationGateway,
    private val session: TelegramBotSession,
    private val prepare: suspend (JsonObject) -> TelegramInboxMessage?,
    private val now: () -> Long = { Clock.System.now().epochSeconds },
) {
    @Volatile private var state = TelegramBotState()
    @Volatile private var nextTypingAttemptAt = 0L

    suspend fun initialize() {
        state = session.load()
        save(state.copy(deliveries = state.deliveries.map {
            if (it.state == TelegramDelivery.State.SENDING) it.copy(state =
                if (it.replacesStatus && invocation(it.invocationId).statusMessageId != null) TelegramDelivery.State.PENDING
                else TelegramDelivery.State.UNKNOWN) else it
        }))
    }

    suspend fun poll() {
        if (state.nextApiAttemptAt > now()) return
        val updates = api.call("getUpdates", buildJsonObject {
            put("offset", state.nextUpdateId); put("timeout", 1); put("limit", 30)
            put("allowed_updates", buildJsonArray { add("message"); add("edited_message"); add("callback_query") })
        }).jsonArray
        receive(updates)
    }

    internal suspend fun receive(updates: JsonArray) {
        for (element in updates) {
            val update = element as? JsonObject ?: continue
            val id = update.long("update_id") ?: continue
            if (id < state.nextUpdateId) continue
            (update["callback_query"] as? JsonObject)?.let { handleCallback(it) }
            val incoming = prepare(update)
            save(state.copy(nextUpdateId = id + 1, inbox = state.inbox + listOfNotNull(incoming)))
        }
    }

    suspend fun typing() {
        if (maxOf(state.nextApiAttemptAt, nextTypingAttemptAt) > now()) return
        for (invocation in state.invocations.filter { it.submitted && !it.completed }.distinctBy { it.binding.key }) {
            try {
                gateway.validate(invocation)
                api.call("sendChatAction", buildJsonObject {
                    put("chat_id", invocation.binding.chatId)
                    invocation.binding.topicId?.let { put("message_thread_id", it) }
                    put("action", "typing")
                })
            } catch (_: TelegramBindingRejected) { Unit }
            catch (error: TelegramApiFailure) {
                if (error.code == 429) {
                    nextTypingAttemptAt = now() + (error.retryAfterSeconds ?: 5).coerceIn(1, 3600)
                    return
                }
            }
            catch (_: TelegramDeliveryUncertain) { Unit }
        }
    }

    suspend fun synchronize() {
        session.verifyLease()
        for (invocation in state.invocations.filter { !it.completed && !current(it) }) {
            if (invocation.submitted) gateway.stop(invocation)
            update(invocation.copy(completed = true, failed = true, statusKey = "runtime.failedTaskLabel"))
        }
        for (binding in connection.bindings.filter { it.enabled }) {
            var active = next(binding)
            if (active == null && !pendingDelivery(binding)) {
                for (entry in state.inbox.filter { it.binding.key == binding.key }.take(50)) {
                    if (entry.binding != binding) {
                        save(state.copy(inbox = state.inbox.filterNot { it.updateId == entry.updateId }))
                        continue
                    }
                    try { gateway.import(connection, entry) } catch (_: ExternalConversationIngressDeferred) { break }
                    val invocations = entry.routes.filter { it in binding.routes && entry.activationRevision == connection.activationRevision }.map { route ->
                        TelegramInvocation(id = telegramId(connection.id, binding.chatId, entry.sourceMessageId, route.agentId.value),
                            connectionId = connection.id, ownerUserId = connection.ownerUserId, binding = binding, route = route,
                            rootMessageId = entry.message.id, sourceMessageId = entry.sourceMessageId, activationRevision = connection.activationRevision)
                    }
                    save(state.copy(inbox = state.inbox.filterNot { it.updateId == entry.updateId },
                        invocations = state.invocations + invocations.filter { candidate -> state.invocations.none { it.id == candidate.id } }))
                    if (invocations.isNotEmpty()) break
                }
                active = next(binding)
            }
            if (active != null && !pendingDelivery(binding, exceptInvocation = active.id)) synchronizeInvocation(active.id)
        }
        if (state.nextApiAttemptAt <= now()) {
            for (id in state.invocations.filter { it.submissionPrepared || it.statusAttempted }.map { it.id }) {
                if (state.nextApiAttemptAt > now()) break
                publishStatus(id)
            }
            for (id in state.deliveries.filter { it.state == TelegramDelivery.State.PENDING && it.retryAtEpochSeconds <= now() }.map { it.id }) {
                if (state.nextApiAttemptAt > now()) break
                deliver(id)
            }
        }
        prune()
    }

    private suspend fun synchronizeInvocation(id: String) {
        var invocation = invocation(id)
        try {
            val name = gateway.validate(invocation)
            if (!invocation.submitted) {
                if (!invocation.submissionPrepared) {
                    val cursor = gateway.cursor(invocation.binding)
                    invocation = invocation.copy(eventCursor = cursor, startedAfterEventSequence = cursor, submissionPrepared = true, agentName = name)
                    update(invocation)
                }
                gateway.submit(invocation)
                invocation = invocation.copy(submitted = true)
                update(invocation)
            }
        } catch (_: ExternalConversationIngressDeferred) {
            return
        } catch (_: TelegramBindingRejected) {
            if (invocation.submitted) gateway.stop(invocation)
            update(invocation.copy(completed = true, failed = true, submissionPrepared = true, statusKey = "runtime.failedTaskLabel"))
            return
        }
        for (entry in gateway.events(invocation)) consumeEvent(id, entry)
        val snapshot = gateway.snapshot(invocation.binding)
        if (snapshot.lastEventSequence <= invocation(id).eventCursor) applySnapshot(id, snapshot)
    }

    private suspend fun consumeEvent(id: String, entry: ConversationRuntimeEventLogEntry) {
        var invocation = invocation(id)
        if (entry.conversationId != invocation.binding.conversationId || entry.sequence <= invocation.eventCursor) return
        val event = entry.event
        if (event is ConversationRuntimeEvent.MessageEmitted && event.turnId?.value == id) {
            val rendering = TelegramMessageRendering(invocation.binding.locale)
            val activities = event.message.content.mapNotNull(rendering::activity)
            invocation = invocation.copy(failed = invocation.failed || event.message.error != null,
                activity = (invocation.activity + activities).takeLast(30))
            if (event.message.role == Conversation.Message.Role.ASSISTANT && event.message.error == null) {
                invocation = invocation.copy(responseTexts = invocation.responseTexts + rendering.messageTexts(event.message))
            }
        }
        invocation = invocation.copy(eventCursor = entry.sequence)
        update(invocation)
    }

    private suspend fun applySnapshot(id: String, snapshot: ConversationRuntimeSnapshot) {
        val invocation = invocation(id)
        check(snapshot.conversationId == invocation.binding.conversationId)
        val tasks = listOfNotNull(snapshot.activeTask, snapshot.continuationTask) + snapshot.pendingTasks + snapshot.activeInsertions
        val ownTasks = tasks.filter { it.turnId.value == id }
        val incident = snapshot.incidents.any { it.task.turnId.value == id }
        val completed = incident || ownTasks.isEmpty()
        val active = snapshot.activeTask?.takeIf { it.turnId.value == id }
        val key = when {
            incident || completed && invocation.failed -> "runtime.failedTaskLabel"
            completed && invocation.stopRequested -> "interpreter.stopped"
            completed -> "runtime.memoryCompletedStatus"
            invocation.stopRequested -> "runtime.stoppingStatus"
            active == null -> "runtime.queuedStatus"
            active.payload is ConversationRuntimeTask.Payload.ToolExecution -> "runtime.toolExecutionStatus"
            active.payload is ConversationRuntimeTask.Payload.LlmCall -> "runtime.modelRequestStatus"
            else -> "runtime.memoryRunningStatus"
        }
        update(invocation.copy(completed = completed, failed = invocation.failed || incident, statusKey = key))
    }

    private suspend fun publishStatus(id: String) {
        val invocation = invocation(id)
        if (!current(invocation) || invocation.completed || invocation.nextStatusAttemptAt > now()) return
        val problem = state.deliveries.any { it.invocationId == id && it.state in setOf(TelegramDelivery.State.UNKNOWN, TelegramDelivery.State.FAILED) }
        val text = TelegramMessageRendering(invocation.binding.locale).status(invocation, problem)
        if (invocation.publishedStatusText == text || invocation.statusMessageId == null && invocation.statusAttempted) return
        val creating = invocation.statusMessageId == null
        update(invocation.copy(statusAttempted = true, nextStatusAttemptAt = now() + 3))
        try {
            val result = api.call(if (creating) "sendMessage" else "editMessageText", buildJsonObject {
                put("chat_id", invocation.binding.chatId); put("text", text); put("parse_mode", "HTML")
                if (creating) addReply(invocation) else put("message_id", invocation.statusMessageId!!)
                put("reply_markup", buildJsonObject { put("inline_keyboard", buildJsonArray {
                    if (!invocation.completed && !invocation.stopRequested) add(buildJsonArray { add(buildJsonObject {
                        put("text", telegramText(invocation.binding.locale, "runtime.stopButton")); put("callback_data", "stop:${invocation.id}")
                    }) })
                }) })
            })
            update(invocation(id).copy(statusMessageId = if (creating) result.jsonObject.long("message_id") else invocation.statusMessageId,
                publishedStatusText = text))
        } catch (error: TelegramApiFailure) {
            if (error.code == 429) {
                backoff(error)
                update(invocation(id).copy(statusAttempted = !creating, nextStatusAttemptAt = state.nextApiAttemptAt))
            } else update(invocation(id).copy(publishedStatusText = text, statusAttempted = !creating))
        } catch (_: TelegramDeliveryUncertain) {
            if (!creating) update(invocation(id).copy(nextStatusAttemptAt = now() + 10))
        }
    }

    private suspend fun deliver(id: String) {
        val delivery = state.deliveries.single { it.id == id }
        if (state.deliveries.takeWhile { it.id != id }.any { it.invocationId == delivery.invocationId &&
                it.state in setOf(TelegramDelivery.State.PENDING, TelegramDelivery.State.SENDING) }) return
        val invocation = invocation(delivery.invocationId)
        try { gateway.validate(invocation) } catch (_: TelegramBindingRejected) {
            updateDelivery(delivery.copy(state = TelegramDelivery.State.FAILED)); return
        }
        val replacing = delivery.replacesStatus && invocation.statusMessageId != null
        if (delivery.replacesStatus && !replacing && invocation.statusAttempted) {
            updateDelivery(delivery.copy(state = TelegramDelivery.State.UNKNOWN)); return
        }
        if (delivery.replacesStatus && !replacing) update(invocation.copy(statusAttempted = true))
        updateDelivery(delivery.copy(state = TelegramDelivery.State.SENDING))
        try {
            val result = api.call(if (replacing) "editMessageText" else "sendMessage", buildJsonObject {
                put("chat_id", invocation.binding.chatId); put("text", delivery.text); put("parse_mode", "HTML")
                put("link_preview_options", buildJsonObject { put("is_disabled", true) })
                if (replacing) put("message_id", invocation.statusMessageId!!) else addReply(invocation)
                put("reply_markup", buildJsonObject { put("inline_keyboard", JsonArray(emptyList())) })
            }).jsonObject
            val messageId = if (replacing) invocation.statusMessageId!! else checkNotNull(result.long("message_id"))
            save(state.copy(
                invocations = state.invocations.map { if (it.id == invocation.id && delivery.replacesStatus)
                    it.copy(statusMessageId = messageId, publishedStatusText = delivery.text) else it },
                deliveries = state.deliveries.map { if (it.id == id) it.copy(state = TelegramDelivery.State.SENT, telegramMessageId = messageId) else it },
            ))
        } catch (error: TelegramApiFailure) {
            if (error.code == 429) {
                backoff(error)
                if (delivery.replacesStatus && !replacing) update(invocation(id = delivery.invocationId).copy(statusAttempted = false))
                updateDelivery(delivery.copy(retryAtEpochSeconds = state.nextApiAttemptAt))
            }
            else updateDelivery(delivery.copy(state = TelegramDelivery.State.FAILED))
        } catch (_: TelegramDeliveryUncertain) {
            updateDelivery(delivery.copy(state = if (replacing) TelegramDelivery.State.PENDING else TelegramDelivery.State.UNKNOWN,
                retryAtEpochSeconds = if (replacing) now() + 10 else 0))
        }
    }

    private suspend fun handleCallback(callback: JsonObject) {
        val message = callback["message"] as? JsonObject
        val from = callback["from"] as? JsonObject
        val id = callback.string("data")?.takeIf { it.startsWith("stop:") }?.removePrefix("stop:")
        val invocation = state.invocations.singleOrNull { it.id == id }
        val authorized = invocation != null && current(invocation) && from?.long("id") == invocation.binding.initiatorTelegramUserId &&
            from.boolean("is_bot") == false && (message?.get("chat") as? JsonObject)?.long("id") == invocation.binding.chatId &&
            message?.long("message_id") == invocation.statusMessageId && invocation.statusMessageId != null
        val stopped = if (authorized && !invocation!!.completed) gateway.stop(invocation) else false
        if (stopped) update(invocation!!.copy(stopRequested = true, statusKey = "runtime.stoppingStatus", nextStatusAttemptAt = 0))
        callback.string("id")?.let { callbackId ->
            try {
                api.call("answerCallbackQuery", buildJsonObject {
                    put("callback_query_id", callbackId)
                    put("text", telegramText(invocation?.binding?.locale ?: "en", if (stopped) "runtime.stoppingStatus" else "runtime.closedStatus"))
                })
            } catch (_: TelegramApiFailure) { Unit } catch (_: TelegramDeliveryUncertain) { Unit }
        }
    }

    private fun JsonObjectBuilder.addReply(invocation: TelegramInvocation) {
        invocation.binding.topicId?.let { put("message_thread_id", it) }
        put("reply_parameters", buildJsonObject { put("message_id", invocation.sourceMessageId); put("allow_sending_without_reply", true) })
    }
    private fun current(invocation: TelegramInvocation): Boolean = connection.enabled && invocation.binding in connection.bindings &&
        invocation.binding.enabled && invocation.route in invocation.binding.routes && invocation.ownerUserId == connection.ownerUserId &&
        invocation.activationRevision == connection.activationRevision
    private fun next(binding: TelegramConversationBinding): TelegramInvocation? = state.invocations.firstOrNull { !it.completed && it.binding.key == binding.key }
    private fun pendingDelivery(binding: TelegramConversationBinding, exceptInvocation: String? = null): Boolean = state.deliveries.any {
        it.invocationId != exceptInvocation && it.state in setOf(TelegramDelivery.State.PENDING, TelegramDelivery.State.SENDING) &&
            invocation(it.invocationId).binding.key == binding.key
    }
    private suspend fun backoff(error: TelegramApiFailure) = save(state.copy(nextApiAttemptAt = now() + (error.retryAfterSeconds ?: 5).coerceIn(1, 3600)))
    private suspend fun prune() {
        val awaiting = state.deliveries.filter { it.state in setOf(TelegramDelivery.State.PENDING, TelegramDelivery.State.SENDING) }.map { it.invocationId }.toSet()
        val retained = state.invocations.filter { !it.completed || it.id in awaiting } + state.invocations.filter { it.completed && it.id !in awaiting }.takeLast(50)
        val ids = retained.map { it.id }.toSet()
        save(state.copy(invocations = retained, deliveries = state.deliveries.filter { it.invocationId in ids },
            inbox = state.inbox.filter { incoming -> connection.bindings.any { it.key == incoming.binding.key } }))
    }
    private fun invocation(id: String): TelegramInvocation = state.invocations.single { it.id == id }
    private suspend fun update(invocation: TelegramInvocation) {
        val additions = if (invocation.completed && state.deliveries.none { it.invocationId == invocation.id }) {
            TelegramMessageRendering(invocation.binding.locale).presentation(invocation).mapIndexed { index, text ->
                TelegramDelivery("${invocation.id}:$index", invocation.id, text, replacesStatus = index == 0)
            }
        } else emptyList()
        save(state.copy(invocations = state.invocations.map { if (it.id == invocation.id) invocation else it }, deliveries = state.deliveries + additions))
    }
    private suspend fun updateDelivery(delivery: TelegramDelivery) = save(state.copy(deliveries = state.deliveries.map { if (it.id == delivery.id) delivery else it }))
    private suspend fun save(value: TelegramBotState) {
        if (value == state) return
        session.save(value); state = value
    }
}
