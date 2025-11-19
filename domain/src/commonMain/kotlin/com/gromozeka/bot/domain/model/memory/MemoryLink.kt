package com.gromozeka.bot.domain.model.memory

import kotlinx.datetime.Instant

/**
 * Relationship edge in the knowledge graph connecting two entities.
 *
 * Represents directed relationships with bi-temporal tracking for versioning and soft deletion.
 *
 * ## Bi-Temporal Model
 * Tracks both when facts are valid in reality and when they're recorded in the system:
 *
 * - **Valid-time dimension**: [validAt] / [invalidAt]
 *   - When is this fact true in the real world?
 *   - Example: "Gromozeka uses Spring AI" becomes valid when migration happened
 *
 * - **Transaction-time dimension**: [createdAt]
 *   - When was this fact recorded in the database?
 *   - Immutable - never changes after creation
 *
 * ## Soft Deletion
 * Setting [invalidAt] marks fact as no longer valid without destroying history.
 * Queries filter `WHERE r.invalid_at IS NULL` to get only current facts.
 *
 * ## Semantic Search
 * [embedding] enables finding relationships by semantic similarity of descriptions.
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property uuid unique relationship identifier (UUIDv4)
 * @property sourceNodeUuid UUID of source entity node
 * @property targetNodeUuid UUID of target entity node
 * @property relationType relationship type label (currently unused - [description] used instead)
 * @property description human-readable relationship description (e.g., "written in", "uses", "created by")
 * @property embedding vector representation of description for semantic search (3072 dimensions, nullable if not yet embedded)
 * @property validAt when this fact became valid in reality (valid-time start, nullable = unknown/always valid)
 * @property invalidAt when this fact became invalid/outdated (valid-time end, nullable = currently valid, set for soft delete)
 * @property createdAt when this relationship was created in database (transaction-time, immutable)
 * @property sources episodic context sources where this relationship was mentioned (conversation IDs, document references, etc.)
 * @property groupId multi-tenancy identifier (must match entity groupId)
 * @property attributes extensible key-value storage for future metadata (currently unused)
 */
data class MemoryLink(
    val uuid: String,
    val sourceNodeUuid: String,
    val targetNodeUuid: String,
    val relationType: String,
    val description: String,
    val embedding: List<Float>?,
    val validAt: Instant?,
    val invalidAt: Instant?,
    val createdAt: Instant,
    val sources: List<String>,
    val groupId: String,
    val attributes: Map<String, Any> = emptyMap()
)
