package com.gromozeka.client

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.ArtifactUpload
import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

interface ArtifactTransferService {
    suspend fun upload(
        conversationId: Conversation.Id,
        upload: ArtifactUpload,
    ): Artifact.Reference

    suspend fun download(id: Artifact.Id): ByteArray

    suspend fun deleteDraft(id: Artifact.Id)
}

class RemoteArtifactTransferService internal constructor(
    private val client: GromozekaWsClient,
) : ArtifactTransferService {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun upload(
        conversationId: Conversation.Id,
        upload: ArtifactUpload,
    ): Artifact.Reference = json.decodeFromString(
        client.uploadArtifact(conversationId, upload)
    )

    override suspend fun download(id: Artifact.Id): ByteArray =
        client.getServerResourceBytes("/api/artifacts/${id.value}/content")

    override suspend fun deleteDraft(id: Artifact.Id) {
        client.deleteServerResource("/api/artifacts/${id.value}")
    }
}
