package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation

fun interface ArtifactReferenceValidator {
    suspend fun validateReferences(
        conversationId: Conversation.Id,
        content: List<Conversation.Message.ContentItem>,
    )
}
