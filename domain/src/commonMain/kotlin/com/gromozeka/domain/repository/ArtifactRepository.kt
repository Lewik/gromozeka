package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.Conversation
import kotlinx.datetime.Instant

interface ArtifactRepository {
    suspend fun save(artifact: Artifact): Artifact

    suspend fun findById(id: Artifact.Id): Artifact?

    suspend fun findByIds(ids: List<Artifact.Id>): List<Artifact>

    suspend fun findByConversation(conversationId: Conversation.Id): List<Artifact>

    suspend fun commit(ids: List<Artifact.Id>, committedAt: Instant)

    suspend fun deleteDraft(id: Artifact.Id): Boolean

    suspend fun findDraftsCreatedBefore(createdBefore: Instant, limit: Int): List<Artifact>
}
