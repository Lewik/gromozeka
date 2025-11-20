package com.gromozeka.infrastructure.ai.model

import kotlinx.serialization.Serializable

@Serializable
data class FileMetadata(
    val fileId: String,
    val sha256: String,
    val relativePath: String,
)
