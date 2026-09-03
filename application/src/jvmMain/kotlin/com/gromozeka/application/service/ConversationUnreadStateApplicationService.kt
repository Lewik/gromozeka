package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationUnreadState
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.ConversationUnreadStateRepository
import com.gromozeka.domain.service.DeclarativeStateChangePublisher
import com.gromozeka.domain.service.DeclarativeStateKey
import com.gromozeka.domain.service.UserConversationUnreadStateService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ConversationUnreadStateApplicationService(
    private val repository: ConversationUnreadStateRepository,
    private val conversationRepository: ConversationRepository,
    private val stateChanges: DeclarativeStateChangePublisher,
) : UserConversationUnreadStateService {
    override suspend fun snapshot(userId: User.Id): ConversationUnreadState = repository.load(userId)

    @Transactional
    override suspend fun markRead(
        userId: User.Id,
        conversationId: Conversation.Id,
    ): ConversationUnreadState {
        val conversation = conversationRepository.findById(conversationId)
            ?: error("Conversation not found: ${conversationId.value}")
        require(Conversation.Participant.User(userId) in conversation.participants) {
            "User ${userId.value} is not connected to conversation ${conversationId.value}"
        }
        if (repository.markRead(conversationId, userId)) {
            stateChanges.publish(DeclarativeStateKey.conversationUnreadState(userId))
        }
        return repository.load(userId)
    }

    suspend fun recordMessage(
        conversation: Conversation,
        message: Conversation.Message,
    ) {
        if (!message.hasUnreadContent()) return

        val authorUserId = (message.author as? Conversation.Message.Author.User)?.userId
        val recipientIds = conversation.participants
            .filterIsInstance<Conversation.Participant.User>()
            .map(Conversation.Participant.User::userId)
            .filterTo(mutableSetOf()) { it != authorUserId }
        repository.markUnread(conversation.id, recipientIds).forEach { userId ->
            stateChanges.publish(DeclarativeStateKey.conversationUnreadState(userId))
        }
    }

    private fun Conversation.Message.hasUnreadContent(): Boolean = content.any { item ->
        when (item) {
            is Conversation.Message.ContentItem.ToolCall,
            is Conversation.Message.ContentItem.ToolResult,
            is Conversation.Message.ContentItem.Thinking,
            -> false

            is Conversation.Message.ContentItem.UserMessage,
            is Conversation.Message.ContentItem.System,
            is Conversation.Message.ContentItem.AssistantMessage,
            is Conversation.Message.ContentItem.ImageItem,
            is Conversation.Message.ContentItem.DocumentItem,
            is Conversation.Message.ContentItem.ArtifactItem,
            is Conversation.Message.ContentItem.ContextCompactionResult,
            is Conversation.Message.ContentItem.UnknownJson,
            -> true
        }
    }
}
