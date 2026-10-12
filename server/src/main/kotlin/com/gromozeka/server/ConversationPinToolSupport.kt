package com.gromozeka.server

import com.gromozeka.domain.model.AgentEndpoint
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationPin
import com.gromozeka.domain.model.ConversationPinLimits
import com.gromozeka.domain.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Tool-local wire validation/formatting, not a second pin API or persistence layer. */
internal object ConversationPinToolSupport {
    const val ID_LIMIT = 128
    const val CURSOR_LIMIT = 512
    const val DEFAULT_PAGE_SIZE = 50
    const val PAGE_LIMIT = 100
    const val PREVIEW_LIMIT = 256
    private val idPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    private val cursorPattern = Regex("^[A-Za-z0-9_-]+$")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    enum class Operation(val suffix: String) {
        LIST("list"), GET("get"), CREATE("create"), UPDATE("update"), DELETE("delete");
        val toolName get() = "grz_conversation_pin_$suffix"
        val required: Set<String> get() = when (this) {
            LIST -> emptySet()
            GET -> setOf("pin_id")
            CREATE -> setOf("text")
            UPDATE -> setOf("pin_id", "expected_revision", "text")
            DELETE -> setOf("pin_id", "expected_revision")
        }
        val keys: Set<String> get() = required + "conversation_id" + if (this == LIST) setOf("cursor", "limit") else emptySet()
    }

    data class Cursor(val conversationId: String, val ordinal: Long, val pinId: String)
    data class Arguments(
        val conversationId: String?,
        val pinId: String? = null,
        val text: String? = null,
        val revision: Long? = null,
        val cursor: Cursor? = null,
        val limit: Int = DEFAULT_PAGE_SIZE,
    )

    fun schema(operation: Operation): String = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            for (key in operation.keys) put(key, buildJsonObject {
                when (key) {
                    "conversation_id", "pin_id" -> {
                        put("type", "string"); put("minLength", 1); put("maxLength", ID_LIMIT); put("pattern", idPattern.pattern)
                    }
                    "text" -> {
                        put("type", "string"); put("minLength", 1); put("maxLength", ConversationPinLimits.MAX_TEXT_LENGTH)
                        put("description", "Nonblank literal text; maximum length is measured in UTF-16 code units (Kotlin String.length). Whitespace is preserved.")
                    }
                    "expected_revision" -> { put("type", "integer"); put("minimum", 1) }
                    "limit" -> { put("type", "integer"); put("minimum", 1); put("maximum", PAGE_LIMIT); put("default", DEFAULT_PAGE_SIZE) }
                    "cursor" -> { put("type", "string"); put("minLength", 1); put("maxLength", CURSOR_LIMIT); put("pattern", cursorPattern.pattern) }
                }
            })
        })
        put("required", JsonArray(operation.required.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }.toString()

    fun parse(operation: Operation, raw: String): Arguments = try {
        // A valid literal field can use six JSON characters per UTF-16 unit. Bound parser work too.
        require(raw.length <= ConversationPinLimits.MAX_TEXT_LENGTH * 6 + 8192)
        val input = Json.parseToJsonElement(raw).jsonObject
        require(input.keys.all { it in operation.keys } && operation.required.all { it in input })
        fun string(key: String): String {
            val value = input[key]
            require(value is JsonPrimitive && value.isString)
            return value.content
        }
        fun identifier(key: String) = string(key).also { require(idPattern.matches(it)) }
        fun integer(key: String): Long {
            val value = input[key]
            require(value is JsonPrimitive && !value.isString)
            return requireNotNull(value.longOrNull)
        }
        Arguments(
            // Only absence means current. Null, empty, whitespace and wrong JSON types all fail.
            conversationId = if ("conversation_id" in input) identifier("conversation_id") else null,
            pinId = if ("pin_id" in input) identifier("pin_id") else null,
            text = if ("text" in input) string("text").also {
                require(it.isNotBlank() && it.length <= ConversationPinLimits.MAX_TEXT_LENGTH)
            } else null,
            revision = if ("expected_revision" in input) integer("expected_revision").also { require(it > 0) } else null,
            cursor = if ("cursor" in input) decodeCursor(string("cursor")) else null,
            limit = if ("limit" in input) integer("limit").also { require(it in 1L..PAGE_LIMIT.toLong()) }.toInt() else DEFAULT_PAGE_SIZE,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        invalidArguments()
    }

    fun requireCursorTarget(cursor: Cursor?, conversationId: Conversation.Id) {
        if (cursor != null && cursor.conversationId != conversationId.value) invalidArguments()
    }

    private fun decodeCursor(value: String): Cursor {
        require(value.length in 1..CURSOR_LIMIT && cursorPattern.matches(value))
        val bytes = decoder.decode(value)
        require(encoder.encodeToString(bytes) == value)
        val json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        val parts = Json.parseToJsonElement(json).jsonArray
        require(parts.size == 4 && parts[0] == JsonPrimitive(1))
        val conversation = parts[1].jsonPrimitive
        val ordinal = parts[2].jsonPrimitive
        val pin = parts[3].jsonPrimitive
        require(conversation.isString && idPattern.matches(conversation.content))
        require(!ordinal.isString && ordinal.longOrNull != null)
        require(pin.isString && idPattern.matches(pin.content))
        return Cursor(conversation.content, ordinal.long, pin.content)
    }

    fun encodeCursor(pin: ConversationPin): String = encoder.encodeToString(JsonArray(listOf(
        JsonPrimitive(1), JsonPrimitive(pin.conversationId.value), JsonPrimitive(pin.ordinal), JsonPrimitive(pin.id.value),
    )).toString().toByteArray(StandardCharsets.UTF_8))

    fun preview(text: String): String {
        var end = minOf(text.length, PREVIEW_LIMIT)
        if (end in 1 until text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return text.substring(0, end)
    }

    fun metadata(pin: ConversationPin): JsonObject {
        if (!idPattern.matches(pin.id.value) || !idPattern.matches(pin.conversationId.value) || pin.revision <= 0 ||
            pin.text.isBlank() || pin.text.length > ConversationPinLimits.MAX_TEXT_LENGTH
        ) operationFailed()
        return buildJsonObject {
            put("pin_id", pin.id.value); put("conversation_id", pin.conversationId.value)
            put("revision", pin.revision); put("ordinal", pin.ordinal)
            put("created_at", pin.createdAt.toString()); put("updated_at", pin.updatedAt.toString())
            put("text_length", pin.text.length)
        }
    }

    fun page(conversationId: Conversation.Id, pins: List<ConversationPin>, arguments: Arguments): JsonObject {
        requireCursorTarget(arguments.cursor, conversationId)
        if (arguments.limit !in 1..PAGE_LIMIT) invalidArguments()
        if (pins.size > ConversationPinLimits.MAX_PINS_PER_CONVERSATION || pins.any { it.conversationId != conversationId } ||
            pins.map { it.id }.distinct().size != pins.size ||
            pins.sumOf { it.text.length.toLong() } > ConversationPinLimits.MAX_TOTAL_TEXT_LENGTH.toLong()
        ) operationFailed()
        val ordered = pins.sortedWith(compareBy<ConversationPin> { it.ordinal }.thenBy { it.id.value })
        val after = arguments.cursor
        val remaining = ordered.filter { after == null || it.ordinal > after.ordinal || (it.ordinal == after.ordinal && it.id.value > after.pinId) }
        val selected = remaining.take(arguments.limit)
        return buildJsonObject {
            put("conversation_id", conversationId.value); put("consistency", "best_effort")
            put("items", JsonArray(selected.map { pin -> JsonObject(metadata(pin) + ("preview" to JsonPrimitive(preview(pin.text)))) }))
            put("next_cursor", if (remaining.size > selected.size) JsonPrimitive(encodeCursor(selected.last())) else JsonNull)
        }
    }

    /** Key material comes exclusively from the trusted execution endpoint, actor and call identity. */
    fun creationKey(source: AgentEndpoint, actor: User.Id, toolCallId: String?): String {
        if (toolCallId.isNullOrBlank()) throw IllegalStateException("Pin create requires a trusted tool-call identity (PIN_OPERATION_FAILED).")
        val material = JsonArray(listOf(source.conversationId.value, source.threadId.value, source.agentId.value, actor.value, toolCallId)
            .map(::JsonPrimitive)).toString().toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(material).joinToString("") { "%02x".format(it) }
        return "pin-tool-v1:$digest"
    }

    fun invalidArguments(): Nothing = throw IllegalArgumentException("Invalid conversation pin arguments (PIN_INVALID_ARGUMENT).")
    fun operationFailed(): Nothing = throw IllegalStateException("Conversation pin operation failed (PIN_OPERATION_FAILED).")
}
