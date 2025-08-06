package com.gromozeka.bot.model

import kotlinx.serialization.Serializable

/**
 * Data class for Gromozeka-specific JSON format used in messages
 */
@Serializable
data class GromozekaJson(
    val fullText: String,
    val ttsText: String? = null,
    val voiceTone: String? = null,
)