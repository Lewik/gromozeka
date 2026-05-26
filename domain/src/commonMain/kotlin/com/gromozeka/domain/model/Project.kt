package com.gromozeka.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Project associated with conversations.
 *
 * Project represents a working directory (codebase, document folder, etc.)
 * where AI agent operates. Conversations are grouped by project.
 *
 * Project persistence is an infrastructure detail. The domain layer works with
 * the unified Project entity without knowing whether fields are indexed,
 * denormalized, or stored as JSON by the repository.
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id unique project identifier (UUIDv7)
 * @property path filesystem path to project root directory
 * @property name human-readable project name
 * @property description optional project notes or purpose
 * @property createdAt timestamp when project was first created
 * @property lastUsedAt timestamp of last conversation in this project
 */
@Serializable
data class Project(
    val id: Id,
    val path: String,
    val name: String,
    val description: String? = null,
    val createdAt: Instant,
    val lastUsedAt: Instant,
) {
    /**
     * Unique project identifier (UUIDv7).
     */
    @Serializable
    @JvmInline
    value class Id(val value: String)
}
