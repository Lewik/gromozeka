package com.gromozeka.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Project associated with conversations.
 *
 * Project represents a working directory (codebase, document folder, etc.)
 * where AI agent operates. Conversations are grouped by project.
 *
 * ## Storage Architecture
 *
 * Project data is stored across three databases (implementation detail):
 * - **SQL**: id, path, timestamps (queryable metadata)
 * - **JSON** (.gromozeka/project.json): name, description, domain_patterns (versioned in Git)
 * - **Neo4j**: denormalized copy (project_id, name) for code spec filtering
 *
 * Repository implementation coordinates all three storage backends transparently.
 * Domain layer works with unified Project entity without knowing storage details.
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
