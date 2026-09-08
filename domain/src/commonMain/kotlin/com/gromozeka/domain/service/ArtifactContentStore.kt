package com.gromozeka.domain.service

import com.gromozeka.domain.model.Artifact

interface ArtifactContentStore {
    suspend fun write(id: Artifact.Id, content: ByteArray)

    suspend fun read(id: Artifact.Id): ByteArray

    suspend fun delete(id: Artifact.Id)

    suspend fun listIds(): Set<Artifact.Id>
}

interface ExternalArtifactContentReader {
    fun supports(source: Artifact.ContentSource): Boolean
    suspend fun read(artifact: Artifact, maximumBytes: Int): ByteArray
}

class ArtifactContentUnavailableException(val reason: String) : RuntimeException(reason)
