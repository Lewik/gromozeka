package com.gromozeka.remote.protocol

import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

@Serializable
sealed interface WorkerGatewayMessage {
    @Serializable
    @SerialName("hello")
    data class Hello(
        val registration: ConversationRuntimeWorkerRegistration,
        val protocolVersion: Int = WORKER_GATEWAY_PROTOCOL_VERSION,
    ) : WorkerGatewayMessage

    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val heartbeatIntervalSeconds: Long,
        val protocolVersion: Int = WORKER_GATEWAY_PROTOCOL_VERSION,
    ) : WorkerGatewayMessage

    @Serializable
    @SerialName("heartbeat")
    data class Heartbeat(
        val sentAt: Instant,
    ) : WorkerGatewayMessage

    @Serializable
    @SerialName("failure")
    data class Failure(
        val code: String,
        val message: String,
    ) : WorkerGatewayMessage
}

const val WORKER_GATEWAY_PROTOCOL_VERSION = 1

@OptIn(ExperimentalSerializationApi::class)
object WorkerGatewayCodec {
    private val cbor = Cbor {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encode(message: WorkerGatewayMessage): ByteArray =
        cbor.encodeToByteArray(WorkerGatewayMessage.serializer(), message)

    fun decode(bytes: ByteArray): WorkerGatewayMessage =
        cbor.decodeFromByteArray(WorkerGatewayMessage.serializer(), bytes)
}
