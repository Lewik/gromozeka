package com.gromozeka.domain.service

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.BinaryContent
import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.Serializable

interface ToolOutputArtifactReader {
    suspend fun read(
        conversationId: Conversation.Id,
        artifactId: Artifact.Id,
        offset: Long,
        limit: Int,
    ): ToolOutputArtifactChunk
}

@Serializable
data class ToolOutputArtifactChunk(
    val artifact: Artifact.Reference,
    val content: BinaryContent,
    val offset: Long,
    val totalBytes: Long,
    val sha256: String?,
)

const val MAX_TOOL_OUTPUT_DOWNLOAD_CHUNK_BYTES = 1024 * 1024
