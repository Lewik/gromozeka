package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.model.ai.AiRuntimeSelection

interface MessageSquashGenerationService {
    suspend fun squashWithAI(
        conversationId: Conversation.Id,
        selectedIds: List<Conversation.Message.Id>,
        squashType: SquashType,
        runtimeSelection: AiRuntimeSelection,
    ): String
}
