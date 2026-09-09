package com.gromozeka.server.telegram

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.repository.TelegramChannelRepository
import com.gromozeka.domain.service.*
import kotlinx.serialization.json.*
import org.springframework.stereotype.Service

@Service
class TelegramRequestEnricher(
    private val repository: TelegramChannelRepository,
    private val conversations: ConversationDomainService,
    private val coordinator: ConversationRuntimeCoordinator,
    private val access: TelegramBindingAccess,
) : ConversationRequestEnricher {
    override suspend fun enrich(conversationId: Conversation.Id, rootUserMessageId: Conversation.Message.Id,
        turnId: ConversationRuntimeTurnId, model: AiModelSpec, request: AiRuntimeRequest): AiRuntimeRequest {
        val channel = conversations.findById(conversationId)?.externalChannel ?: return request
        if (channel.provider != "telegram") return request
        val invocation = repository.find(channel.connectionId)?.invocations?.singleOrNull { it.id == turnId.value }
            ?: error("Telegram invocation context is unavailable")
        check(!invocation.completed && invocation.rootMessageId == rootUserMessageId && invocation.binding.conversationId == conversationId)
        check(request.options.toolContext["userId"] == invocation.ownerUserId.value)
        check(request.options.toolContext["agentDefinitionId"] == invocation.route.agentId.value)
        access.requireUser(invocation)
        val ownMessageIds = mutableSetOf(rootUserMessageId)
        var cursor = invocation.startedAfterEventSequence
        var count = 0
        while (true) {
            val events = coordinator.listEventLogEntries(conversationId, cursor, 100)
            if (events.isEmpty()) break
            for (entry in events) {
                val event = entry.event
                if (event is ConversationRuntimeEvent.MessageEmitted && event.turnId == turnId) ownMessageIds += event.message.id
            }
            cursor = events.last().sequence
            count += events.size
            check(count <= 100000) { "Telegram turn event history exceeds the safety limit" }
        }
        return materialize(invocation, model, request, ownMessageIds)
    }

    internal fun materialize(invocation: TelegramInvocation, model: AiModelSpec, request: AiRuntimeRequest,
        ownMessageIds: Set<Conversation.Message.Id>): AiRuntimeRequest {
        val window = checkNotNull(model.contextWindowTokens) { "Telegram context requires a known model context window" }
        val modeId = if (invocation.route.writeAllowed) "mode_writable" else "mode_readonly"
        val mode = MessageInstructionGroup.defaults().flatMap { it.controls }.single { it.data.id == modeId }.data
        val policy = buildString {
            appendLine(TELEGRAM_SAFETY_INSTRUCTION)
            appendLine("CURRENT AUTHORIZED REQUEST: message ${invocation.rootMessageId.value}; Telegram initiator ${invocation.binding.initiatorTelegramUserId}; acting Gromozeka user ${invocation.ownerUserId.value}.")
            appendLine("Execution mode: ${mode.title}. ${mode.description}.")
            if (invocation.route.additionalInstruction.isNotBlank()) appendLine("Additional channel instruction:\n${invocation.route.additionalInstruction}")
        }
        val root = request.messages.single { it.id == invocation.rootMessageId }
        val current = request.messages.filter { it.id in ownMessageIds }
        check(current.firstOrNull()?.id == root.id) { "Current Telegram request must precede its continuations" }
        val outputReserve = request.options.maxOutputTokens ?: model.maxOutputTokens ?: 4096
        val mandatory = estimate(Json.encodeToString(current)) + request.systemPrompts.sumOf(::estimate) + estimate(policy) +
            request.tools.sumOf { estimate(Json.encodeToString(it.definition)) } + outputReserve.toLong() + window / 20 + 2048
        check(mandatory < window) { "Telegram invocation exceeds the model context budget" }
        var remaining = minOf(window.toLong() * invocation.route.contextPercent / 100, window - mandatory)
        val selected = mutableListOf<Conversation.Message>()
        for (message in request.messages.filter { it.id !in ownMessageIds }.asReversed()) {
            val cost = estimate(transcript(message).toString()) + 128
            if (cost > remaining) break
            selected += message
            remaining -= cost
        }
        val context = buildJsonObject {
            put("kind", "untrusted_group_history")
            put("messages", JsonArray(selected.asReversed().map(::transcript)))
            root.providerMetadata["telegramReplyQuotation"]?.let { put("reply_quotation", it) }
        }
        val attachments = selected.asReversed().flatMap { it.content.filterIsInstance<Conversation.Message.ContentItem.ArtifactItem>() }
        val authorized = root.copy(content = listOf(Conversation.Message.ContentItem.UserMessage(context.toString())) + attachments +
            Conversation.Message.ContentItem.UserMessage("CURRENT AUTHORIZED REQUEST (message ${root.id.value}, Telegram user ${invocation.binding.initiatorTelegramUserId}):") + root.content)
        return request.copy(systemPrompts = request.systemPrompts + policy, messages = listOf(authorized) + current.drop(1))
    }

    private fun transcript(message: Conversation.Message): JsonObject = buildJsonObject {
        put("messageId", message.id.value)
        put("role", message.role.name)
        message.author?.let { put("author", Json.encodeToJsonElement(it)) }
        put("content", Json.encodeToJsonElement(message.content.filterNot {
            it is Conversation.Message.ContentItem.Thinking && it.kind == Conversation.Message.ContentItem.Thinking.Kind.REDACTED
        }.map { if (it is Conversation.Message.ContentItem.Thinking) it.copy(signature = null) else it }))
    }

    private fun estimate(text: String): Long = text.encodeToByteArray().size.toLong()
}
