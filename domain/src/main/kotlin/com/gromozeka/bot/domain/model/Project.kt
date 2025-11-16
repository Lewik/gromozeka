package com.gromozeka.bot.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Project associated with conversations.
 *
 * Project represents a working directory (codebase, document folder, etc.)
 * where AI agent operates. Conversations are grouped by project.
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id unique project identifier (UUIDv7)
 * @property path filesystem path to project root directory
 * @property name human-readable project name (can be blank, use displayName() for UI)
 * @property description optional project notes or purpose
 * @property favorite true if user marked project as favorite (for quick access)
 * @property archived true if project is archived (hidden from active project list)
 * @property createdAt timestamp when project was first used
 * @property lastUsedAt timestamp of last conversation in this project
 */
@Serializable
data class Project(
    val id: Id,
    val path: String,
    val name: String,
    val description: String? = null,
    val favorite: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Instant,
    val lastUsedAt: Instant,
) {
    /**
     * Unique project identifier (UUIDv7).
     */
    @Serializable
    @JvmInline
    value class Id(val value: String)

    /**
     * Returns display name for UI - uses name if present, otherwise last path segment.
     *
     * Example: path="/home/user/myproject", name="" → returns "myproject"
     */
    fun displayName(): String = name.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/')
}
