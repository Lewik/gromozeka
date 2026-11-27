package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult
import org.springframework.stereotype.Service

@Service
class ConversationContinuationStateService(
    private val temporalContext: MessageTemporalContextService,
) {
    fun captureMessages(
        messages: List<Conversation.Message>,
        inherited: ContextCompactionResult.ContinuationState = ContextCompactionResult.ContinuationState(),
    ): ContextCompactionResult.ContinuationState = ContextCompactionResult.ContinuationState(
        previousHumanMessageAt = temporalContext.latestHumanMessageAt(messages, inherited.previousHumanMessageAt),
    )

    fun captureHeaders(
        headers: List<CompactionMessageHeader>,
        inherited: ContextCompactionResult.ContinuationState = headers
            .firstOrNull { it.node.isFull }
            ?.continuationState
            ?: ContextCompactionResult.ContinuationState(),
    ): ContextCompactionResult.ContinuationState = ContextCompactionResult.ContinuationState(
        previousHumanMessageAt = headers.asReversed().firstNotNullOfOrNull { it.humanMessageAt }
            ?: inherited.previousHumanMessageAt,
    )
}
