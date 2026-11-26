package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult
import com.gromozeka.domain.model.MessageInstructionGroup
import org.springframework.stereotype.Service

@Service
class StickyMessageInstructionService {
    fun materialize(
        messages: List<Conversation.Message>,
        groups: List<MessageInstructionGroup>,
        continuationState: ContextCompactionResult.ContinuationState = ContextCompactionResult.ContinuationState(),
    ): List<Conversation.Message> {
        val stickyGroupByInstructionId = groups
            .asSequence()
            .filter { it.retentionMode == MessageInstructionGroup.RetentionMode.STICKY_LATEST }
            .flatMap { group -> group.controls.asSequence().map { it.data.id to group.id } }
            .toMap()
        if (stickyGroupByInstructionId.isEmpty()) return messages

        val winnerIndexes = buildSet {
            val seenGroups = mutableSetOf<String>()
            continuationState.stickyInstructionCandidates.indices.reversed().forEach { index ->
                val instruction = continuationState.stickyInstructionCandidates[index]
                stickyGroupByInstructionId[instruction.id]?.let { groupId ->
                    if (seenGroups.add(groupId)) add(index)
                }
            }
        }
        val inheritedWinners = continuationState.stickyInstructionCandidates
            .filterIndexed { index, _ -> index in winnerIndexes }
        val seededMessages = if (inheritedWinners.isEmpty() || messages.isEmpty()) messages else messages.mapIndexed { index, message ->
            if (index == 0) message.copy(instructions = inheritedWinners + message.instructions) else message
        }

        val latestMessageIndexByGroup = buildMap {
            seededMessages.forEachIndexed { messageIndex, message ->
                message.instructions.forEach { instruction ->
                    if (instruction is Conversation.Message.Instruction.UserInstruction) {
                        stickyGroupByInstructionId[instruction.id]?.let { groupId ->
                            put(groupId, messageIndex)
                        }
                    }
                }
            }
        }

        return seededMessages.mapIndexed { messageIndex, message ->
            val materializedInstructions = message.instructions.filterNot { instruction ->
                if (instruction !is Conversation.Message.Instruction.UserInstruction) return@filterNot false
                val groupId = stickyGroupByInstructionId[instruction.id] ?: return@filterNot false
                latestMessageIndexByGroup[groupId] != messageIndex
            }
            if (materializedInstructions.size == message.instructions.size) {
                message
            } else {
                message.copy(instructions = materializedInstructions)
            }
        }
    }

    /** Keep only the latest occurrence of each candidate ID, preserving those occurrence positions. */
    fun captureCandidates(
        messages: List<Conversation.Message>,
        inherited: List<Conversation.Message.Instruction.UserInstruction> = emptyList(),
    ): List<Conversation.Message.Instruction.UserInstruction> {
        val latest = linkedMapOf<String, Conversation.Message.Instruction.UserInstruction>()
        fun record(instruction: Conversation.Message.Instruction.UserInstruction) {
            latest.remove(instruction.id)
            latest[instruction.id] = instruction
        }
        inherited.forEach(::record)
        messages.asSequence().flatMap { it.instructions.asSequence() }
            .filterIsInstance<Conversation.Message.Instruction.UserInstruction>()
            .forEach(::record)
        return latest.values.toList()
    }
}
