package com.gromozeka.infrastructure.ai.model

import kotlinx.serialization.Serializable

@Serializable
data class ThreadMetadata(
    val threadId: String,
)
