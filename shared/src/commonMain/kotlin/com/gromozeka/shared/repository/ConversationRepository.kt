package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.Conversation
import com.gromozeka.shared.domain.Project

interface ConversationRepository {
    suspend fun create(conversation: Conversation): Conversation
    suspend fun findById(id: Conversation.Id): Conversation?
    suspend fun findByProject(projectId: Project.Id): List<Conversation>
    suspend fun delete(id: Conversation.Id)
    suspend fun updateCurrentThread(id: Conversation.Id, threadId: Conversation.Thread.Id)
    suspend fun updateDisplayName(id: Conversation.Id, displayName: String)
}
