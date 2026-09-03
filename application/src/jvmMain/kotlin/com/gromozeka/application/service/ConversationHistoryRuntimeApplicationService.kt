package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.ConversationHistoryMutation
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.springframework.stereotype.Service

@Service
class ConversationHistoryRuntimeApplicationService(
    private val runtimeDispatcher: ConversationRuntimeDispatcher,
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
) {
    suspend fun editMessage(
        actorUser: User,
        taskId: ConversationRuntimeTask.Id,
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
        newContent: List<Conversation.Message.ContentItem>,
    ) = execute(
        actorUser = actorUser,
        taskId = taskId,
        conversationId = conversationId,
        mutation = ConversationHistoryMutation.Edit(messageId, newContent),
    )

    suspend fun deleteMessages(
        actorUser: User,
        taskId: ConversationRuntimeTask.Id,
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>,
    ) = execute(
        actorUser = actorUser,
        taskId = taskId,
        conversationId = conversationId,
        mutation = ConversationHistoryMutation.Delete(messageIds),
    )

    suspend fun compactMessages(
        actorUser: User,
        taskId: ConversationRuntimeTask.Id,
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>,
        strategy: SquashType,
    ) = execute(
        actorUser = actorUser,
        taskId = taskId,
        conversationId = conversationId,
        mutation = ConversationHistoryMutation.Compact(messageIds, strategy),
    )

    private suspend fun execute(
        actorUser: User,
        taskId: ConversationRuntimeTask.Id,
        conversationId: Conversation.Id,
        mutation: ConversationHistoryMutation,
    ) {
        if (findCompletedMutation(conversationId, taskId) != null) return
        runtimeCoordinator.findTaskIncident(conversationId, taskId)?.let { incident ->
            error(incident.message)
        }

        var cursor = runtimeCoordinator.snapshot(conversationId).lastEventSequence
        val accepted = runtimeDispatcher.submitHistoryMutation(
            conversationId = conversationId,
            taskId = taskId,
            mutation = mutation,
            actorUserId = actorUser.id,
        )
        if (!accepted) {
            if (findCompletedMutation(conversationId, taskId) != null) return
            runtimeCoordinator.findTaskIncident(conversationId, taskId)?.let { incident ->
                error(incident.message)
            }
            check(runtimeCoordinator.snapshot(conversationId).containsTask(taskId)) {
                "Conversation history mutation was rejected: ${taskId.value}"
            }
        }

        while (true) {
            currentCoroutineContext().ensureActive()
            val entries = runtimeCoordinator.listEventLogEntries(
                conversationId = conversationId,
                afterSequence = cursor,
                limit = EVENT_BATCH_SIZE,
            )
            entries.firstOrNull { entry ->
                (entry.event as? ConversationRuntimeEvent.HistoryChanged)?.taskId == taskId
            }?.let {
                return
            }
            entries.lastOrNull()?.let { cursor = it.sequence }
            runtimeCoordinator.findTaskIncident(conversationId, taskId)?.let { incident ->
                error(incident.message)
            }
            delay(RESULT_POLL_INTERVAL_MILLIS)
        }
    }

    private fun ConversationRuntimeSnapshot.containsTask(
        taskId: ConversationRuntimeTask.Id,
    ): Boolean =
        activeTask?.id == taskId ||
            activeInsertions.any { it.id == taskId } ||
            continuationTask?.id == taskId ||
            pendingTasks.any { it.id == taskId }

    private suspend fun findCompletedMutation(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
    ): ConversationRuntimeEvent.HistoryChanged? =
        runtimeCoordinator.listEventLogEntries(
            conversationId = conversationId,
            afterSequence = null,
            limit = EVENT_BATCH_SIZE,
        ).asSequence()
            .mapNotNull { it.event as? ConversationRuntimeEvent.HistoryChanged }
            .firstOrNull { it.taskId == taskId }

    private companion object {
        const val EVENT_BATCH_SIZE = 1_000
        const val RESULT_POLL_INTERVAL_MILLIS = 50L
    }
}
