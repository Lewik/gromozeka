package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.service.AiConfigurationProvider

internal data class ConversationMemoryAvailability(
    val remember: Boolean,
    val recall: Boolean,
) {
    val enabled: Boolean get() = remember || recall
}

internal fun conversationMemoryAvailability(
    conversation: Conversation,
    settings: UserProfile.MemorySettings,
    configuration: AiConfigurationProvider,
): ConversationMemoryAvailability {
    if (conversation.externalChannel != null) {
        return ConversationMemoryAvailability(remember = false, recall = false)
    }
    return ConversationMemoryAvailability(
        remember = settings.autoRemember &&
            configuration.availableRuntimeSelectionFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE) != null,
        recall = settings.autoRecall &&
            configuration.availableRuntimeSelectionFor(AiRuntimeAssignment.Purpose.MEMORY_READ) != null,
    )
}
