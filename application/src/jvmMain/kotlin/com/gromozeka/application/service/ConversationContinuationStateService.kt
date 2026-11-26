package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult
import org.springframework.stereotype.Service

@Service
class ConversationContinuationStateService(
    private val stickyInstructions: StickyMessageInstructionService,
    private val temporalContext: MessageTemporalContextService,
) {
    fun captureMessages(
        messages: List<Conversation.Message>,
        inherited: ContextCompactionResult.ContinuationState = ContextCompactionResult.ContinuationState(),
    ): ContextCompactionResult.ContinuationState = ContextCompactionResult.ContinuationState(
        stickyInstructionCandidates = stickyInstructions.captureCandidates(
            messages,
            inherited.stickyInstructionCandidates,
        ),
        previousHumanMessageAt = temporalContext.latestHumanMessageAt(messages, inherited.previousHumanMessageAt),
    )

    fun captureHeaders(
        headers: List<CompactionMessageHeader>,
        inherited: ContextCompactionResult.ContinuationState = headers
            .firstOrNull { it.node.isFull }
            ?.continuationState
            ?: ContextCompactionResult.ContinuationState(),
    ): ContextCompactionResult.ContinuationState {
        val latest = linkedMapOf<String, Conversation.Message.Instruction.UserInstruction>()
        fun record(instruction: Conversation.Message.Instruction.UserInstruction) {
            latest.remove(instruction.id)
            latest[instruction.id] = instruction
        }
        inherited.stickyInstructionCandidates.forEach(::record)
        headers.asSequence().flatMap { it.userInstructions.asSequence() }.forEach(::record)
        return ContextCompactionResult.ContinuationState(
            stickyInstructionCandidates = latest.values.toList(),
            previousHumanMessageAt = headers.asReversed().firstNotNullOfOrNull { it.humanMessageAt }
                ?: inherited.previousHumanMessageAt,
        )
    }
}
