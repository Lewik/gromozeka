package com.gromozeka.remote.protocol

import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.tool.AiToolDescriptor
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
    @SerialName("tool_catalog_updated")
    data class ToolCatalogUpdated(
        val tools: List<AiToolDescriptor>,
    ) : WorkerGatewayMessage

    @Serializable
    @SerialName("request")
    data class Request(
        val id: String,
        val operation: WorkerGatewayOperation,
        val payload: ByteArray,
    ) : WorkerGatewayMessage {
        init {
            require(id.isNotBlank()) { "Worker Gateway request id must not be blank" }
        }

        override fun equals(other: Any?): Boolean =
            other is Request &&
                id == other.id &&
                operation == other.operation &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int =
            31 * (31 * id.hashCode() + operation.hashCode()) + payload.contentHashCode()
    }

    @Serializable
    @SerialName("response")
    data class Response(
        val requestId: String,
        val status: Status,
        val payload: ByteArray? = null,
        val errorCode: String? = null,
        val errorMessage: String? = null,
    ) : WorkerGatewayMessage {
        init {
            require(requestId.isNotBlank()) { "Worker Gateway response request id must not be blank" }
            require(
                (status == Status.SUCCEEDED && payload != null && errorCode == null && errorMessage == null) ||
                    (
                        status == Status.FAILED &&
                            payload == null &&
                            !errorCode.isNullOrBlank() &&
                            !errorMessage.isNullOrBlank()
                        )
            ) {
                "Worker Gateway response payload does not match status $status"
            }
        }

        override fun equals(other: Any?): Boolean =
            other is Response &&
                requestId == other.requestId &&
                status == other.status &&
                payload.contentEqualsNullable(other.payload) &&
                errorCode == other.errorCode &&
                errorMessage == other.errorMessage

        override fun hashCode(): Int {
            var result = requestId.hashCode()
            result = 31 * result + status.hashCode()
            result = 31 * result + (payload?.contentHashCode() ?: 0)
            result = 31 * result + (errorCode?.hashCode() ?: 0)
            result = 31 * result + (errorMessage?.hashCode() ?: 0)
            return result
        }

        @Serializable
        enum class Status {
            SUCCEEDED,
            FAILED,
        }
    }

    @Serializable
    @SerialName("failure")
    data class Failure(
        val code: String,
        val message: String,
    ) : WorkerGatewayMessage
}

@Serializable
enum class WorkerGatewayOperation {
    WORKER_CONTROL,
    AI_REQUEST_RESPONSE,
    TOOL_EXECUTION,
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

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    when {
        this == null -> other == null
        other == null -> false
        else -> contentEquals(other)
    }
