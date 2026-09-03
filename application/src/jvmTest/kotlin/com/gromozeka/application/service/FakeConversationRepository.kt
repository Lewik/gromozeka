package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.ConversationRepository
import kotlin.time.Clock

internal class FakeConversationRepository : ConversationRepository {
    val conversations = linkedMapOf<Conversation.Id, Conversation>()

    override suspend fun create(conversation: Conversation): Conversation {
        check(conversations.putIfAbsent(conversation.id, conversation) == null)
        return conversation
    }

    override suspend fun findById(id: Conversation.Id): Conversation? = conversations[id]

    override suspend fun findByProject(projectId: Project.Id): List<Conversation> =
        conversations.values
            .filter { it.projectId == projectId }
            .sortedByDescending(Conversation::updatedAt)

    override suspend fun delete(id: Conversation.Id) {
        conversations.remove(id)
    }

    override suspend fun updateCurrentThread(id: Conversation.Id, threadId: Conversation.Thread.Id) {
        conversations[id] = conversations.getValue(id).copy(
            currentThread = threadId,
            updatedAt = Clock.System.now(),
        )
    }

    override suspend fun updateDisplayName(id: Conversation.Id, displayName: String) {
        conversations[id] = conversations.getValue(id).copy(
            displayName = displayName,
            updatedAt = Clock.System.now(),
        )
    }

    override suspend fun updateParticipants(
        id: Conversation.Id,
        participants: Set<Conversation.Participant>,
    ) {
        conversations[id] = conversations.getValue(id).copy(
            participants = participants,
            updatedAt = Clock.System.now(),
        )
    }

    override suspend fun touch(id: Conversation.Id) {
        conversations[id] = conversations.getValue(id).copy(updatedAt = Clock.System.now())
    }
}
