package com.gromozeka.remote.protocol

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder

@Serializable
enum class RemoteProtocolEncoding {
    CBOR,
    JSON
}

@OptIn(ExperimentalSerializationApi::class)
object RemoteProtocolCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "payloadType"
    }

    private val cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = true
        alwaysUseByteString = true
    }

    fun encodeClientBinary(envelope: GromozekaClientEnvelope): ByteArray =
        cbor.encodeToByteArray(GromozekaClientEnvelope.serializer(), envelope)

    fun decodeClientBinary(bytes: ByteArray): GromozekaClientEnvelope =
        cbor.decodeFromByteArray(GromozekaClientEnvelope.serializer(), bytes)

    fun encodeServerBinary(envelope: GromozekaServerEnvelope): ByteArray =
        cbor.encodeToByteArray(GromozekaServerEnvelope.serializer(), envelope)

    fun decodeServerBinary(bytes: ByteArray): GromozekaServerEnvelope =
        cbor.decodeFromByteArray(GromozekaServerEnvelope.serializer(), bytes)

    fun encodeClientText(envelope: GromozekaClientEnvelope): String =
        json.encodeToString(GromozekaClientEnvelope.serializer(), envelope)

    fun decodeClientText(text: String): GromozekaClientEnvelope =
        json.decodeFromString(GromozekaClientEnvelope.serializer(), text)

    fun encodeServerText(envelope: GromozekaServerEnvelope): String =
        json.encodeToString(GromozekaServerEnvelope.serializer(), envelope)

    fun decodeServerText(text: String): GromozekaServerEnvelope =
        json.decodeFromString(GromozekaServerEnvelope.serializer(), text)
}

@OptIn(ExperimentalEncodingApi::class)
object ProtocolByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ProtocolByteArray", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        if (encoder is JsonEncoder) {
            encoder.encodeString(Base64.Default.encode(value))
        } else {
            encoder.encodeSerializableValue(ByteArraySerializer(), value)
        }
    }

    override fun deserialize(decoder: Decoder): ByteArray =
        if (decoder is JsonDecoder) {
            Base64.Default.decode(decoder.decodeString())
        } else {
            decoder.decodeSerializableValue(ByteArraySerializer())
        }
}
