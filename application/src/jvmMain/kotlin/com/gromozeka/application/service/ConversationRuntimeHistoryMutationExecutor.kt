package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationHistoryMutation
import org.springframework.stereotype.Service

@Service
class ConversationRuntimeHistoryMutationExecutor(
    private val conversationService: ConversationApplicationService,
    private val messageSquashService: MessageSquashService,
) {
    internal suspend fun replaceExternalMessage(originalId: Conversation.Message.Id, message: Conversation.Message) {
        conversationService.replaceExternalRuntimeMessage(originalId, message)
    }

    internal suspend fun execute(
        conversationId: Conversation.Id,
        mutation: ConversationHistoryMutation,
        task: com.gromozeka.domain.service.ConversationRuntimeTask,
        beforeCommit: suspend () -> Unit,
    ): Conversation = when (mutation) {
        is ConversationHistoryMutation.Edit -> conversationService.editRuntimeHistory(
            conversationId = conversationId,
            messageId = mutation.messageId,
            newContent = mutation.newContent,
        ) ?: error("Conversation not found: ${conversationId.value}")

        is ConversationHistoryMutation.Delete -> conversationService.deleteRuntimeHistory(
            conversationId = conversationId,
            messageIds = mutation.messageIds,
        ) ?: error("Conversation not found: ${conversationId.value}")

        is ConversationHistoryMutation.Compact -> messageSquashService.compactRuntimeHistory(
            conversationId = conversationId,
            messageIds = mutation.messageIds,
            strategy = mutation.strategy,
            checkpointId = Conversation.Message.Id("${task.id.value}:compaction"),
            requestContext = mapOf("userId" to task.actorUserId?.value),
            beforeCommit = beforeCommit,
        )
    }
}
