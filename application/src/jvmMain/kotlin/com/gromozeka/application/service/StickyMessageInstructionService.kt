package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageInstructionGroup
import org.springframework.stereotype.Service

@Service
class StickyMessageInstructionService {
    fun materialize(
        messages: List<Conversation.Message>,
        groups: List<MessageInstructionGroup>,
    ): List<Conversation.Message> {
        val stickyGroupByInstructionId = groups
            .asSequence()
            .filter { it.retentionMode == MessageInstructionGroup.RetentionMode.STICKY_LATEST }
            .flatMap { group -> group.controls.asSequence().map { it.data.id to group.id } }
            .toMap()
        if (stickyGroupByInstructionId.isEmpty()) return messages

        val latestMessageIndexByGroup = buildMap {
            messages.forEachIndexed { messageIndex, message ->
                message.instructions.forEach { instruction ->
                    if (instruction is Conversation.Message.Instruction.UserInstruction) {
                        stickyGroupByInstructionId[instruction.id]?.let { groupId ->
                            put(groupId, messageIndex)
                        }
                    }
                }
            }
        }

        return messages.mapIndexed { messageIndex, message ->
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
}
