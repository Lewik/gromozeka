package com.gromozeka.domain.model.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject

object JsonElementTransportSerializer : KSerializer<JsonElement> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("JsonElementTransport", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: JsonElement) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(value)
        } else {
            encoder.encodeString(Json.encodeToString(JsonElement.serializer(), value))
        }
    }

    override fun deserialize(decoder: Decoder): JsonElement =
        if (decoder is JsonDecoder) {
            decoder.decodeJsonElement()
        } else {
            Json.parseToJsonElement(decoder.decodeString())
        }
}

object JsonObjectTransportSerializer : KSerializer<JsonObject> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("JsonObjectTransport", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: JsonObject) {
        JsonElementTransportSerializer.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): JsonObject =
        JsonElementTransportSerializer.deserialize(decoder) as? JsonObject
            ?: error("Expected JsonObject")
}
