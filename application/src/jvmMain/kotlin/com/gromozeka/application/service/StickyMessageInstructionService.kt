package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult
import com.gromozeka.domain.model.MessageInstructionGroup
import com.gromozeka.domain.model.isFullContextCompaction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class StickyMessageInstructionService {
    private val toolCallPairs = ToolCallPairingService()

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
        val seededMessages = seedInheritedWinners(messages, inheritedWinners)

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

    private fun seedInheritedWinners(
        messages: List<Conversation.Message>,
        inheritedWinners: List<Conversation.Message.Instruction.UserInstruction>,
    ): List<Conversation.Message> {
        if (inheritedWinners.isEmpty() || messages.isEmpty()) return messages

        val existingCarrier = messages.indexOfFirst(Conversation.Message::isStickyContinuationCarrier)
        if (existingCarrier >= 0) return messages.mapIndexed { index, message ->
            if (index == existingCarrier) message.copy(instructions = inheritedWinners) else message
        }

        val checkpointIndex = messages.indexOfLast(Conversation.Message::isFullContextCompaction)
        val anchor = messages[checkpointIndex.takeIf { it >= 0 } ?: 0]
        val insertionIndex = if (checkpointIndex < 0) 0 else {
            val checkpointGroup = toolCallPairs.includePairedToolMessages(messages, listOf(anchor.id))
            messages.indexOfLast { it.id in checkpointGroup } + 1
        }
        val carrier = Conversation.Message(
            id = Conversation.Message.Id(UUID.nameUUIDFromBytes(
                "$STICKY_CONTINUATION_ID_NAMESPACE:${anchor.id.value}".encodeToByteArray(),
            ).toString()),
            conversationId = anchor.conversationId,
            role = Conversation.Message.Role.RUNTIME,
            content = listOf(Conversation.Message.ContentItem.RuntimeJson(JsonObject(mapOf(
                STICKY_CONTINUATION_KIND_KEY to JsonPrimitive(STICKY_CONTINUATION_KIND),
            )))),
            instructions = inheritedWinners,
            createdAt = anchor.createdAt,
        )
        return messages.toMutableList().apply { add(insertionIndex, carrier) }
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

private const val STICKY_CONTINUATION_ID_NAMESPACE = "gromozeka:sticky-continuation"
private const val STICKY_CONTINUATION_KIND_KEY = "kind"
private const val STICKY_CONTINUATION_KIND = "sticky_instruction_continuation"

private fun Conversation.Message.isStickyContinuationCarrier(): Boolean =
    role == Conversation.Message.Role.RUNTIME &&
        (content.singleOrNull() as? Conversation.Message.ContentItem.RuntimeJson)
            ?.json == JsonObject(mapOf(STICKY_CONTINUATION_KIND_KEY to JsonPrimitive(STICKY_CONTINUATION_KIND)))
