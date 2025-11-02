package com.gromozeka.shared.domain

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class Agent(
    val id: Id,
    val name: String,
    val systemPrompt: String,
    val description: String? = null,
    val isBuiltin: Boolean = false,
    val usageCount: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)
}