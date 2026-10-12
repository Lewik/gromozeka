package com.gromozeka.domain.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/** Independent text snapshot. Source is inert provenance, never a live message reference. */
@Serializable
data class ConversationPin(
    val id: Id,
    val conversationId: Conversation.Id,
    val text: String,
    val ordinal: Long,
    val revision: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val source: ConversationPinSource? = null,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String) {
        init { require(value.isNotBlank()) { "Conversation pin id must not be blank" } }
    }
}

@Serializable
data class ConversationPinSource(
    val conversationId: Conversation.Id,
    val messageId: Conversation.Message.Id,
)

/** UTF-16 code units (Kotlin String.length), not tokens or Unicode code points. */
object ConversationPinLimits {
    const val MAX_TEXT_LENGTH = 32_000
    const val MAX_PINS_PER_CONVERSATION = 100
    const val MAX_TOTAL_TEXT_LENGTH = 128_000
    const val MAX_CREATION_KEY_LENGTH = 512

    fun validateText(text: String) {
        require(text.isNotBlank()) { "Conversation pin text must not be blank" }
        if (text.length > MAX_TEXT_LENGTH) throw ConversationPinLimitException()
    }

    fun validateCreationKey(key: String?) {
        require(key == null || (key.isNotBlank() && key.length <= MAX_CREATION_KEY_LENGTH)) {
            "Invalid conversation pin creation key"
        }
    }
}

/** Content-free failures: never attach storage/provider exceptions or pin text. */
sealed class ConversationPinException(message: String) : IllegalStateException(message)
class ConversationPinNotFoundException : ConversationPinException("Conversation pin not found (CONVERSATION_PIN_NOT_FOUND).")
class ConversationPinConflictException : ConversationPinException("Conversation pin revision or creation key conflict (CONVERSATION_PIN_CONFLICT).")
class ConversationPinLimitException : ConversationPinException("Conversation pin limit exceeded (CONVERSATION_PIN_LIMIT).")
class ConversationPinOperationFailedException : ConversationPinException("Conversation pin operation failed (CONVERSATION_PIN_OPERATION_FAILED).")
class ConversationPinReadFailedException : ConversationPinException("Conversation pins are unavailable (CONVERSATION_PINS_UNAVAILABLE).")
