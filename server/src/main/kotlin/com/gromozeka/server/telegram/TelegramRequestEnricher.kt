package com.gromozeka.server.telegram

import com.gromozeka.domain.model.*
import com.gromozeka.application.service.ContextSnapshotUnavailableException
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRequestTokenEstimate
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.repository.TelegramChannelRepository
import com.gromozeka.domain.service.*
import kotlinx.serialization.json.*
import org.springframework.stereotype.Service
import org.springframework.core.annotation.Order

@Service
@Order(0) // Filter/authorize the channel history before late live-context enrichers.
class TelegramRequestEnricher(
    private val repository: TelegramChannelRepository,
    private val conversations: ConversationDomainService,
    private val coordinator: ConversationRuntimeCoordinator,
    private val access: TelegramBindingAccess,
) : ConversationRequestEnricher {
    override suspend fun enrichSnapshot(conversationId: Conversation.Id, rootUserMessageId: Conversation.Message.Id,
        turnId: ConversationRuntimeTurnId, model: AiModelSpec, request: AiRuntimeRequest): AiRuntimeRequest {
        // A synthetic snapshot turn cannot authorize Telegram's live-invocation history filter.
        if (conversations.findById(conversationId)?.externalChannel?.provider == "telegram") {
            throw ContextSnapshotUnavailableException("Telegram context does not support read-only snapshots")
        }
        return request
    }

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
        ownMessageIds += coordinator.findEmittedMessageIds(conversationId, turnId, invocation.startedAfterEventSequence)
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
        val fixedRequest = request.copy(
            systemPrompts = request.systemPrompts + policy,
            messages = request.messages.filter { message ->
                message.id in ownMessageIds || message.role == Conversation.Message.Role.RUNTIME
            },
        )
        val mandatory = AiRequestTokenEstimate.input(fixedRequest).toLong() +
            outputReserve + window / 20 + 2048
        check(mandatory < window) { "Telegram invocation exceeds the model context budget" }
        var remaining = minOf(window.toLong() * invocation.route.contextPercent / 100, window - mandatory)
        val selected = mutableListOf<Conversation.Message>()
        for (message in request.messages.filter {
            it.id !in ownMessageIds && it.role != Conversation.Message.Role.RUNTIME
        }.asReversed()) {
            val attachments = message.content.filterIsInstance<Conversation.Message.ContentItem.ArtifactItem>()
            val attachmentCost = if (attachments.isEmpty()) 0 else AiRequestTokenEstimate.message(
                message.copy(content = attachments, instructions = emptyList()),
            )
            val cost = AiRequestTokenEstimate.text(transcript(message).toString()).toLong() + attachmentCost + 32
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
        return request.copy(
            systemPrompts = request.systemPrompts + policy,
            messages = request.messages.mapNotNull { message ->
                when {
                    message.id == root.id -> authorized
                    message.id in ownMessageIds || message.role == Conversation.Message.Role.RUNTIME -> message
                    else -> null
                }
            },
        )
    }

    private fun transcript(message: Conversation.Message): JsonObject = buildJsonObject {
        put("messageId", message.id.value)
        put("role", message.role.name)
        message.author?.let { put("author", Json.encodeToJsonElement(it)) }
        put("content", Json.encodeToJsonElement(message.content.filterNot {
            it is Conversation.Message.ContentItem.Thinking
        }))
    }
}
