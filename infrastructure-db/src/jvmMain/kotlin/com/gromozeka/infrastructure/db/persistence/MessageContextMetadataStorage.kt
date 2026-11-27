package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageContextMetadata
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun Conversation.Message.contextMetadataJson(json: Json): String {
    val metadata = MessageContextMetadata.from(this)
    require(metadata.userInstructionIds.none { '\u0000' in it } &&
        metadata.nativeUsage?.containsNulString() != true) {
        "Message context metadata contains NUL and cannot be queried safely: ${id.value}"
    }
    return json.encodeToString(metadata)
}

private fun JsonElement.containsNulString(): Boolean = when (this) {
    is JsonObject -> entries.any { (key, value) -> '\u0000' in key || value.containsNulString() }
    is JsonArray -> any { it.containsNulString() }
    is JsonPrimitive -> isString && '\u0000' in content
}
