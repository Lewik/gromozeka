package com.gromozeka.shared.domain.project

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class Project(
    val id: Id,
    val path: String,
    val name: String,
    val description: String? = null,
    val favorite: Boolean = false,
    val archived: Boolean = false,
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, String>? = null,
    val settings: Map<String, String>? = null,
    val statistics: Map<String, String>? = null,
    val createdAt: Instant,
    val lastUsedAt: Instant,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)

    fun displayName(): String = name.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/')
}