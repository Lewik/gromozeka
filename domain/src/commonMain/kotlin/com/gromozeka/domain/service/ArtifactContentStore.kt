package com.gromozeka.domain.service

import com.gromozeka.domain.model.Artifact

interface ArtifactContentStore {
    suspend fun write(id: Artifact.Id, content: ByteArray)

    suspend fun read(id: Artifact.Id): ByteArray

    suspend fun delete(id: Artifact.Id)

    suspend fun listIds(): Set<Artifact.Id>
}
