package com.gromozeka.bot.shared

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class SharedMessage(
    val id: String,
    val content: String,
    val timestamp: Instant = Clock.System.now()
)