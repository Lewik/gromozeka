package com.gromozeka.domain.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
data class Artifact(
    val id: Id,
    val projectId: Project.Id,
    val conversationId: Conversation.Id,
    val createdByUserId: User.Id?,
    val fileName: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val purpose: Purpose,
    val state: State = State.DRAFT,
    val createdAt: Instant,
    val committedAt: Instant? = null,
) {
    init {
        require(fileName.isNotBlank()) { "Artifact file name must not be blank" }
        require(mediaType.isNotBlank()) { "Artifact media type must not be blank" }
        require(sizeBytes >= 0) { "Artifact size must not be negative" }
        require(sha256.matches(SHA256_PATTERN)) { "Artifact SHA-256 must contain 64 hexadecimal characters" }
        require((state == State.COMMITTED) == (committedAt != null)) {
            "Committed artifacts must have committedAt and draft artifacts must not"
        }
    }

    fun reference(): Reference = Reference(
        id = id,
        fileName = fileName,
        mediaType = mediaType,
        sizeBytes = sizeBytes,
        purpose = purpose,
        kind = Kind.fromMediaType(mediaType),
    )

    @Serializable
    @JvmInline
    value class Id(val value: String) {
        init {
            require(value.isNotBlank()) { "Artifact id must not be blank" }
        }
    }

    @Serializable
    enum class Purpose {
        USER_ATTACHMENT,
        USER_SCREENSHOT,
        TOOL_OUTPUT,
        TOOL_SCREENSHOT,
    }

    @Serializable
    enum class State {
        DRAFT,
        COMMITTED,
    }

    @Serializable
    enum class Kind {
        IMAGE,
        DOCUMENT,
        FILE;

        companion object {
            fun fromMediaType(mediaType: String): Kind = when {
                mediaType.substringBefore(';').trim().startsWith("image/") -> IMAGE
                mediaType.substringBefore(';').trim() == "application/pdf" -> DOCUMENT
                mediaType.substringBefore(';').trim().startsWith("text/") -> DOCUMENT
                else -> FILE
            }
        }
    }

    @Serializable
    data class Reference(
        val id: Id,
        val fileName: String,
        val mediaType: String,
        val sizeBytes: Long,
        val purpose: Purpose,
        val kind: Kind,
    )

    companion object {
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

data class ArtifactUpload(
    val fileName: String,
    val mediaType: String,
    val content: ByteArray,
    val purpose: Artifact.Purpose,
)

object ArtifactLimits {
    const val MAX_FILE_BYTES = 25 * 1024 * 1024
    const val MAX_ARTIFACTS_PER_MESSAGE = 10
    const val MAX_TOTAL_BYTES_PER_MESSAGE = 50L * 1024 * 1024
}
