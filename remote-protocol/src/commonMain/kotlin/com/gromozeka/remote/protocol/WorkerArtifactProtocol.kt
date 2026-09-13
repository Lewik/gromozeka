package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ToolOutputArtifactChunk
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

@Serializable
data class WorkerArtifactReadRequest(
    val conversationId: Conversation.Id,
    val artifactId: Artifact.Id,
    val offset: Long,
    val limit: Int,
)

@OptIn(ExperimentalSerializationApi::class)
object WorkerArtifactCodec {
    private val cbor = Cbor { encodeDefaults = true }

    fun encodeRequest(request: WorkerArtifactReadRequest): ByteArray = cbor.encodeToByteArray(request)
    fun decodeRequest(bytes: ByteArray): WorkerArtifactReadRequest = cbor.decodeFromByteArray(bytes)
    fun encodeResponse(chunk: ToolOutputArtifactChunk): ByteArray = cbor.encodeToByteArray(chunk)
    fun decodeResponse(bytes: ByteArray): ToolOutputArtifactChunk = cbor.decodeFromByteArray(bytes)
}
