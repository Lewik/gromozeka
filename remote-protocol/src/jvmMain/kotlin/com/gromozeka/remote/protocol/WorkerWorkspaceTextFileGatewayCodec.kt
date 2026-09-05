package com.gromozeka.remote.protocol

import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.WorkerWorkspaceTextFileHandler
import com.gromozeka.domain.service.WorkerWorkspaceTextFileReadRequest
import com.gromozeka.domain.service.WorkspaceTextFile
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

@OptIn(ExperimentalSerializationApi::class)
object WorkerWorkspaceTextFileGatewayCodec {
    private val cbor = Cbor {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encodeRequest(request: WorkerWorkspaceTextFileReadRequest): ByteArray =
        cbor.encodeToByteArray(request)

    fun decodeResult(payload: ByteArray): WorkspaceTextFile =
        cbor.decodeFromByteArray(payload)

    suspend fun execute(
        payload: ByteArray,
        identity: ConversationRuntimeWorkerIdentity,
        handler: WorkerWorkspaceTextFileHandler,
    ): ByteArray {
        val request = cbor.decodeFromByteArray<WorkerWorkspaceTextFileReadRequest>(payload)
        require(request.target.workerId == identity.workerId) {
            "Workspace text file request targets another Worker"
        }
        return cbor.encodeToByteArray(handler.read(request))
    }
}
