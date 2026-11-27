package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.MessageInstructionGroup
import com.gromozeka.domain.model.isFullContextCompaction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class StickyMessageInstructionService {
    fun materialize(
        messages: List<Conversation.Message>,
        groups: List<MessageInstructionGroup>,
    ): List<Conversation.Message> {
        val stickyGroupByInstructionId = stickyGroupByInstructionId(groups)
        if (stickyGroupByInstructionId.isEmpty()) return messages

        val occurrencesByGroup = mutableMapOf<String, MutableList<StickyOccurrence>>()
        messages.forEachIndexed { messageIndex, message ->
            message.instructions.forEachIndexed { instructionIndex, instruction ->
                if (instruction is Conversation.Message.Instruction.UserInstruction) {
                    stickyGroupByInstructionId[instruction.id]?.let { groupId ->
                        occurrencesByGroup.getOrPut(groupId, ::mutableListOf).add(
                            StickyOccurrence(messageIndex, instructionIndex, instruction),
                        )
                    }
                }
            }
        }
        val retainedOccurrences = buildSet {
            occurrencesByGroup.values.forEach { occurrences ->
                val current = occurrences.last().instruction
                // Repeated composer selections are semantically unchanged. Keep the first copy of the
                // current value run so they do not move an otherwise cacheable conversation prefix.
                var firstCurrent = occurrences.lastIndex
                while (firstCurrent > 0 && occurrences[firstCurrent - 1].instruction == current) firstCurrent--
                val retained = occurrences[firstCurrent]
                add(retained.messageIndex to retained.instructionIndex)
            }
        }

        return messages.mapIndexed { messageIndex, message ->
            val materializedInstructions = message.instructions.filterIndexed { instructionIndex, instruction ->
                instruction !is Conversation.Message.Instruction.UserInstruction ||
                    stickyGroupByInstructionId[instruction.id] == null ||
                    (messageIndex to instructionIndex) in retainedOccurrences
            }
            if (materializedInstructions.size == message.instructions.size) {
                message
            } else {
                message.copy(instructions = materializedInstructions)
            }
        }
    }

    internal fun createContinuationCarrier(
        checkpoint: Conversation.Message,
        groups: List<MessageInstructionGroup>,
        sourceHeaders: List<CompactionMessageHeader>,
    ): Conversation.Message? {
        require(checkpoint.role == Conversation.Message.Role.ASSISTANT && checkpoint.isFullContextCompaction()) {
            "Sticky continuation carrier requires a full assistant checkpoint"
        }
        val inheritedWinners = currentWinners(
            sourceHeaders.flatMap(CompactionMessageHeader::userInstructions),
            stickyGroupByInstructionId(groups),
        )
        if (inheritedWinners.isEmpty()) return null
        return Conversation.Message(
            id = Conversation.Message.Id(UUID.nameUUIDFromBytes(
                "$STICKY_CONTINUATION_ID_NAMESPACE:${checkpoint.id.value}".encodeToByteArray(),
            ).toString()),
            conversationId = checkpoint.conversationId,
            role = Conversation.Message.Role.RUNTIME,
            author = Conversation.Message.Author.Runtime(),
            content = listOf(Conversation.Message.ContentItem.RuntimeJson(JsonObject(mapOf(
                STICKY_CONTINUATION_KIND_KEY to JsonPrimitive(STICKY_CONTINUATION_KIND),
            )))),
            instructions = inheritedWinners,
            createdAt = checkpoint.createdAt,
        )
    }

    private fun stickyGroupByInstructionId(groups: List<MessageInstructionGroup>): Map<String, String> = groups
        .asSequence()
        .filter { it.retentionMode == MessageInstructionGroup.RetentionMode.STICKY_LATEST }
        .flatMap { group -> group.controls.asSequence().map { it.data.id to group.id } }
        .toMap()

    private fun currentWinners(
        candidates: List<Conversation.Message.Instruction.UserInstruction>,
        stickyGroupByInstructionId: Map<String, String>,
    ): List<Conversation.Message.Instruction.UserInstruction> {
        val winnerIndexes = buildSet {
            val seenGroups = mutableSetOf<String>()
            candidates.indices.reversed().forEach { index ->
                stickyGroupByInstructionId[candidates[index].id]?.let { groupId ->
                    if (seenGroups.add(groupId)) add(index)
                }
            }
        }
        return candidates.filterIndexed { index, _ -> index in winnerIndexes }
    }
}

private data class StickyOccurrence(
    val messageIndex: Int,
    val instructionIndex: Int,
    val instruction: Conversation.Message.Instruction.UserInstruction,
)

private const val STICKY_CONTINUATION_ID_NAMESPACE = "gromozeka:sticky-continuation"
private const val STICKY_CONTINUATION_KIND_KEY = "kind"
private const val STICKY_CONTINUATION_KIND = "sticky_instruction_continuation"
