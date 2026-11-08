package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.shared.utils.sha256
import kotlinx.serialization.json.JsonPrimitive

private const val SYNTHETIC_ID_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
private const val SYNTHETIC_ID_SPACE = 13_537_086_546_263_552L // 62^9
private val portableId = Regex("[A-Za-z0-9]{9}")

/** A repeatable pairing ID, not a replacement for the command, monitor or message identity. */
fun syntheticToolCallId(stableKey: String): ContentItem.ToolCall.Id {
    require(stableKey.isNotBlank()) { "Synthetic tool call requires a stable key" }
    var value = stableKey.sha256().fold(0L) { remainder, digit ->
        (remainder * 16 + digit.digitToInt(16)) % SYNTHETIC_ID_SPACE
    }
    val characters = CharArray(9)
    for (index in characters.indices.reversed()) {
        characters[index] = SYNTHETIC_ID_ALPHABET[(value % 62).toInt()]
        value /= 62
    }
    return ContentItem.ToolCall.Id(characters.concatToString())
}

private fun Message.isSyntheticToolPair(): Boolean =
    providerMetadata["synthetic"] == JsonPrimitive(true) &&
        providerMetadata["syntheticKind"] in setOf(
            JsonPrimitive("command_task_completion"), JsonPrimitive("command_monitor_update"), JsonPrimitive("memory"),
        )

/** Old stored notifications keep their identities; only their model-facing copy is normalized. */
internal fun List<Message>.withPortableSyntheticToolCallIds(): List<Message> {
    if (none { it.isSyntheticToolPair() }) return this
    val ids = mutableMapOf<ContentItem.ToolCall.Id, ContentItem.ToolCall.Id>()
    fun target(message: Message, id: ContentItem.ToolCall.Id): ContentItem.ToolCall.Id =
        if (!message.isSyntheticToolPair()) id else ids.getOrPut(id) {
            if (portableId.matches(id.value)) id else syntheticToolCallId(id.value)
        }

    val origins = mutableMapOf<ContentItem.ToolCall.Id, ContentItem.ToolCall.Id>()
    val calls = mutableMapOf<ContentItem.ToolCall.Id, Boolean>()
    for (message in this) {
        for (item in message.content) {
            val original = when (item) {
                is ContentItem.ToolCall -> item.id
                is ContentItem.ToolResult -> item.toolUseId
                else -> continue
            }
            val mapped = target(message, original)
            val previous = origins.put(mapped, original)
            check(previous == null || previous == original) { "Synthetic tool call ID collision" }
            if (item is ContentItem.ToolCall) {
                val synthetic = message.isSyntheticToolPair()
                val previousCall = calls.put(mapped, synthetic)
                check(previousCall == null || (!previousCall && !synthetic)) {
                    "Synthetic tool call ID is shared by distinct calls"
                }
            }
        }
    }
    return map { message ->
        if (!message.isSyntheticToolPair()) message else message.copy(content = message.content.map { item ->
            when (item) {
                is ContentItem.ToolCall -> item.copy(id = target(message, item.id))
                is ContentItem.ToolResult -> item.copy(toolUseId = target(message, item.toolUseId))
                else -> item
            }
        })
    }
}
