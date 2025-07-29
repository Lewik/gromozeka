package com.gromozeka.bot.model

import kotlinx.serialization.Serializable

@Serializable
data class FileMetadata(
    val fileId: String,
    val sha256: String,
    val relativePath: String,
)
