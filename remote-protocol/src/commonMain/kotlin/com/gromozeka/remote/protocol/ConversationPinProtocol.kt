package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationPin
import com.gromozeka.domain.model.ConversationPinSource
import com.gromozeka.domain.model.Project
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("list_conversation_pins")
data class ListConversationPinsRequest(val conversationId: Conversation.Id) : ClientRequest

@Serializable
@SerialName("get_conversation_pin")
data class GetConversationPinRequest(
    val conversationId: Conversation.Id,
    val pinId: ConversationPin.Id,
) : ClientRequest

@Serializable
@SerialName("create_conversation_pin")
data class CreateConversationPinRequest(
    val conversationId: Conversation.Id,
    val text: String,
    val source: ConversationPinSource? = null,
    // Internal retry identity; this is not an editable pin property.
    val creationKey: String? = null,
) : ClientRequest

@Serializable
@SerialName("update_conversation_pin")
data class UpdateConversationPinRequest(
    val conversationId: Conversation.Id,
    val pinId: ConversationPin.Id,
    val expectedRevision: Long,
    val text: String,
) : ClientRequest

@Serializable
@SerialName("delete_conversation_pin")
data class DeleteConversationPinRequest(
    val conversationId: Conversation.Id,
    val pinId: ConversationPin.Id,
    val expectedRevision: Long,
) : ClientRequest

/** Stable content-free categories carried in the existing ErrorResponse.type field. */
enum class ConversationPinErrorCode {
    PIN_NOT_FOUND,
    PIN_CONFLICT,
    PIN_LIMIT_EXCEEDED,
    PIN_TARGET_UNAVAILABLE,
    PIN_OPERATION_FAILED,
}

@Serializable
@SerialName("conversation_pins")
data class ConversationPinsResponse(val pins: List<ConversationPin>) : ServerResponse

@Serializable
@SerialName("conversation_pin")
data class ConversationPinResponse(val pin: ConversationPin?) : ServerResponse

/** A separate operation: an older Server must not silently create a conversation without its pins. */
@Serializable
@SerialName("create_conversation_with_copied_pins")
data class CreateConversationWithCopiedPinsRequest(
    val projectId: Project.Id,
    val participants: Set<Conversation.Participant>,
    val sourceConversationId: Conversation.Id,
    val displayName: String = "",
) : ClientRequest
