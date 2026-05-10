package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.SquashType

interface MessageSquashGenerationService {
    suspend fun squashWithAI(
        conversationId: Conversation.Id,
        selectedIds: List<Conversation.Message.Id>,
        squashType: SquashType,
        aiProvider: String,
        modelName: String,
        projectPath: String?,
    ): String
}
