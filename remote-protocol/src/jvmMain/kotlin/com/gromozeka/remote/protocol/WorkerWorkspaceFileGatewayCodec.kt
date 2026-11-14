package com.gromozeka.remote.protocol

import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.WorkerWorkspaceFileHandler
import com.gromozeka.domain.service.WorkerWorkspaceFileReadRequest
import com.gromozeka.domain.service.WorkspaceFile
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

@OptIn(ExperimentalSerializationApi::class)
object WorkerWorkspaceFileGatewayCodec {
    private val cbor = Cbor {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encodeRequest(request: WorkerWorkspaceFileReadRequest): ByteArray =
        cbor.encodeToByteArray(request)

    fun decodeResult(payload: ByteArray): WorkspaceFile =
        cbor.decodeFromByteArray(payload)

    suspend fun execute(
        payload: ByteArray,
        identity: ConversationRuntimeWorkerIdentity,
        handler: WorkerWorkspaceFileHandler,
    ): ByteArray {
        val request = cbor.decodeFromByteArray<WorkerWorkspaceFileReadRequest>(payload)
        require(request.target.workerId == identity.workerId) {
            "Workspace file request targets another Worker"
        }
        return cbor.encodeToByteArray(handler.read(request))
    }
}
