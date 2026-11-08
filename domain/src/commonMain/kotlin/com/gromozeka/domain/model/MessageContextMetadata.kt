package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Stored, body-free facts needed before selecting messages for model preparation. */
@Serializable
data class MessageContextMetadata(
    val hasCompaction: Boolean,
    val humanInput: Boolean,
    val userText: Boolean,
    val userInstructionIds: List<String>,
    val nativeUsage: JsonObject?,
) {
    companion object {
        fun from(message: Message): MessageContextMetadata = MessageContextMetadata(
            hasCompaction = message.compactions().isNotEmpty() || message.originalCollaborationMessage().compactions().isNotEmpty(),
            humanInput = message.role == Message.Role.USER &&
                message.content.any { it is Message.ContentItem.UserMessage || it is Message.ContentItem.VisualInteraction } &&
                message.providerMetadata["synthetic"] != JsonPrimitive(true) &&
                message.instructions.none { it is Message.Instruction.Source.Agent },
            userText = message.role == Message.Role.USER && message.content.any { it is Message.ContentItem.UserMessage },
            userInstructionIds = message.instructions.filterIsInstance<Message.Instruction.UserInstruction>().map { it.id },
            nativeUsage = if (message.providerMetadata["provider"] == JsonPrimitive("OPENAI_SUBSCRIPTION")) buildJsonObject {
                put("connectionId", message.providerMetadata["connectionId"] ?: JsonNull)
                put("model", message.providerMetadata["model"] ?: JsonNull)
                put("tokens", message.providerMetadata["openaiSubscriptionContextTokens"] ?: JsonNull)
                put("estimate", message.providerMetadata["openaiSubscriptionContextEstimate"] ?: JsonNull)
            } else null,
        )
    }
}

data class MessageContextHeader(val id: Message.Id, val metadata: MessageContextMetadata)

/**
 * A sufficient sparse raw history, not an already projected provider request.
 * Checkpoints retain coverage/replay provenance. Extra covered anchors preserve the existing
 * sticky-instruction, temporal-context and one-use secret placement before provider projection.
 */
fun selectModelHistoryMessageIds(
    headers: List<MessageContextHeader>,
    checkpoints: List<Message>,
    nativeProvider: String?,
): Set<Message.Id> {
    val checkpointIds = headers.filter { it.metadata.hasCompaction }.mapTo(mutableSetOf()) { it.id }
    require(checkpoints.size == checkpointIds.size && checkpoints.mapTo(mutableSetOf()) { it.id } == checkpointIds) {
        "Incomplete or foreign compaction metadata for conversation history"
    }
    val byId = checkpoints.associateBy { it.id }
    val rawIndex = ConversationCompactionIndex(headers.map { CompactionNode(it.id, byId[it.id]?.compactions().orEmpty()) })
    val originalIndex = ConversationCompactionIndex(headers.map {
        CompactionNode(it.id, byId[it.id]?.originalCollaborationMessage()?.compactions().orEmpty())
    })
    val required = originalIndex.requiredHistoryIds(nativeProvider).toMutableSet()
    // Tool discovery projects the displayed history, while provider replay restores original content.
    rawIndex.project().mapTo(required) { it.id }
    required += checkpointIds
    // Compaction estimates also inspect selectively covered usage observations after the last full checkpoint.
    if (nativeProvider == "OPENAI_SUBSCRIPTION") {
        val boundary = headers.indexOfLast { byId[it.id]?.isFullContextCompaction() == true }
        val scopes = mutableSetOf<Pair<String, String>>()
        for (header in headers.drop(boundary.coerceAtLeast(0)).asReversed()) {
            val usage = header.metadata.nativeUsage ?: continue
            val connection = (usage["connectionId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val model = (usage["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val scope = connection to model
            if (scope !in scopes && usage.requiresOriginalObservation()) {
                required += header.id
                scopes += scope
            }
        }
    }

    val latestInstructionMessages = mutableMapOf<String, Message.Id>()
    val previousHumanMessages = mutableMapOf<Message.Id, Message.Id>()
    var previousHuman: Message.Id? = null
    var lastUserText: Message.Id? = null
    for (header in headers) {
        header.metadata.userInstructionIds.forEach { latestInstructionMessages[it] = header.id }
        if (header.metadata.humanInput) {
            previousHuman?.let { previousHumanMessages[header.id] = it }
            previousHuman = header.id
        }
        if (header.metadata.userText) lastUserText = header.id
    }
    required += latestInstructionMessages.values
    lastUserText?.let(required::add)
    // Only retained messages need an exact predecessor; added covered predecessors do not recurse.
    required += required.mapNotNull(previousHumanMessages::get)
    return required
}

private fun JsonObject.requiresOriginalObservation(): Boolean {
    val tokens = this["tokens"] ?: return false
    // Preserve malformed candidates so pruning cannot hide the original consumer's validation error.
    if (tokens !is JsonPrimitive) return true
    if (tokens.longOrNull == null) return false
    val estimate = this["estimate"] ?: return false
    return estimate !is JsonPrimitive || estimate.longOrNull != null
}
