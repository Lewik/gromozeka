package com.gromozeka.shared.domain

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Software project or workspace for organizing conversations.
 *
 * Projects group related conversations and provide context for AI interactions.
 * Each project maps to a file system path and can be marked as favorite or archived.
 *
 * Projects track last usage time for sorting by recency and can have custom
 * display names separate from the file system path.
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id unique project identifier (time-based UUID for sortability)
 * @property path absolute file system path to project directory
 * @property name custom display name (empty string uses path-derived name via displayName())
 * @property description optional project description or notes
 * @property favorite true if marked as favorite for quick access, false otherwise
 * @property archived true if archived (hidden from main list), false for active projects
 * @property createdAt timestamp when project was first created (immutable)
 * @property lastUsedAt timestamp of last conversation activity (updated on conversation creation/message)
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
    @Serializable
    @JvmInline
    value class Id(val value: String)

    /**
     * Returns display name for UI presentation.
     *
     * Uses custom [name] if set (non-blank), otherwise extracts directory name
     * from [path] (last path component).
     *
     * @return human-readable project name
     */
    fun displayName(): String = name.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/')
}
