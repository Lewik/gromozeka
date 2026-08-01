package com.gromozeka.remote.protocol

import com.gromozeka.domain.service.WorkerAudioCaptureHandler
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.WorkerAudioCaptureRequest
import com.gromozeka.domain.service.WorkerAudioCaptureResult
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

@OptIn(ExperimentalSerializationApi::class)
object WorkerAudioCaptureGatewayCodec {
    private val cbor = Cbor {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encodeRequest(request: WorkerAudioCaptureRequest): ByteArray =
        cbor.encodeToByteArray(request)

    fun decodeResult(payload: ByteArray): WorkerAudioCaptureResult =
        cbor.decodeFromByteArray(payload)

    suspend fun execute(
        payload: ByteArray,
        identity: ConversationRuntimeWorkerIdentity,
        handler: WorkerAudioCaptureHandler,
    ): ByteArray {
        val request = cbor.decodeFromByteArray<WorkerAudioCaptureRequest>(payload)
        require(request.target == identity) {
            "Worker audio capture request targets another Worker session"
        }
        return cbor.encodeToByteArray(handler.handle(request))
    }
}
