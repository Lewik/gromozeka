package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationContext
import com.gromozeka.domain.model.VisualObservation
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.originalCollaborationMessages
import com.gromozeka.domain.service.ConversationRequestEnricher
import com.gromozeka.domain.service.ConversationRuntimeTurnId
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.visual.VisualRepository
import com.gromozeka.shared.uuid.uuid7
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import kotlin.time.Clock

/** Observes accepted state only. The foreground Runtime persists a new observation at its safe boundary. */
@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.server.enabled"], havingValue = "true", matchIfMissing = true)
@Order(Ordered.LOWEST_PRECEDENCE)
class VisualRequestEnricher(private val repository: VisualRepository) : ConversationRequestEnricher {
    override suspend fun enrich(
        conversationId: Conversation.Id,
        rootUserMessageId: Conversation.Message.Id,
        turnId: ConversationRuntimeTurnId,
        model: AiModelSpec,
        request: AiRuntimeRequest,
    ): AiRuntimeRequest = observe(conversationId, request)

    override suspend fun enrichSnapshot(
        conversationId: Conversation.Id,
        rootUserMessageId: Conversation.Message.Id,
        turnId: ConversationRuntimeTurnId,
        model: AiModelSpec,
        request: AiRuntimeRequest,
    ): AiRuntimeRequest = observe(conversationId, request)

    private suspend fun observe(conversationId: Conversation.Id, request: AiRuntimeRequest): AiRuntimeRequest {
        check(request.options.toolContext[TOOL_CONTEXT_CONVERSATION_ID] == conversationId.value) {
            "Visual context must match the authorized conversation"
        }
        val current = repository.list(conversationId).sortedBy { it.id }.map {
            VisualObservation(it.id, it.title, it.status, it.revision, it.state)
        }
        val previous = ConversationContext(request.originalCollaborationMessages()).messages()
            .lastOrNull { it.role == Conversation.Message.Role.VISUAL_UPDATED }
            ?.content?.filterIsInstance<Conversation.Message.ContentItem.VisualUpdated>()?.singleOrNull()
        if (current.isEmpty() && previous == null) return request
        if (previous != null && sameState(previous.visuals, current)) return request
        val closed = previous?.visuals.orEmpty().map { it.id }.filter { id -> current.none { it.id == id } }
        val now = Clock.System.now()
        val message = Conversation.Message(
            id = Conversation.Message.Id(uuid7()), conversationId = conversationId,
            role = Conversation.Message.Role.VISUAL_UPDATED,
            author = Conversation.Message.Author.Runtime(),
            content = listOf(Conversation.Message.ContentItem.VisualUpdated(now, current, closed)),
            createdAt = now,
        )
        return request.copy(messages = request.messages + message)
    }

    private fun sameState(previous: List<VisualObservation>, current: List<VisualObservation>): Boolean =
        previous.size == current.size && previous.zip(current).all { (before, after) ->
            before.id == after.id && before.title == after.title && before.status == after.status && before.state == after.state
        }
}
