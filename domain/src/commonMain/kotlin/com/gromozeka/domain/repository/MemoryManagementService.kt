package com.gromozeka.domain.repository

/**
 * Domain service for high-level knowledge graph management operations.
 *
 * Provides user-facing operations for managing facts, entities, and relationships
 * in the knowledge graph. Designed as MCP (Model Context Protocol) tool interface,
 * exposing operations to LLMs with human-readable feedback.
 *
 * ## Design Rationale: String Returns
 *
 * Methods return String (not domain types) because:
 * 1. **MCP Tool Interface** - LLMs consume these as tools and need textual feedback
 * 2. **Human-readable results** - Status messages are meant for user display
 * 3. **Multi-purpose output** - Combines success confirmation, error messages, and data
 *
 * Example: `"Successfully added fact: 'Gromozeka' -[written in]-> 'Kotlin'"`
 *
 * ## Architecture Note
 *
 * This service has mixed responsibilities (orchestration + presentation logic).
 * Consider refactoring:
 * - Move to Application layer as use case orchestrator
 * - Return domain types (Result<T>, Success/Failure sealed classes)
 * - Format messages in Presentation layer (MCP tool adapters)
 *
 * Current design prioritizes pragmatic MCP integration over strict layering.
 *
 * ## Bi-temporal Semantics
 *
 * Operations support bi-temporal model:
 * - [addFactDirectly] sets validAt to current time (fact becomes valid now)
 * - [invalidateFact] sets invalidAt to current time (soft delete)
 * - Historical queries can filter by validAt/invalidAt to see facts "as of" specific time
 */
interface MemoryManagementService {

    /**
     * Adds a fact directly to the knowledge graph without LLM extraction.
     *
     * **Tool exposure:** `add_memory_link`
     *
     * Creates source and target entities (if needed) and relationship between them.
     * Uses MERGE semantics - updates existing entities on name collision.
     *
     * This is a TRANSACTIONAL operation - entities and relationship created atomically.
     *
     * ## Temporal Semantics
     *
     * **IMPORTANT:** Temporal parameters apply ONLY to the relationship, NOT to entities.
     * - **Entities (nodes):** Always created with `validAt=ALWAYS_VALID_FROM, invalidAt=STILL_VALID`
     *   (entities are timeless concepts like "Kotlin", "TestProject_Beta")
     * - **Relationship (edge):** Gets temporal parameters from method arguments
     *   (relationships are facts with validity periods like "uses PostgreSQL from 2020 to 2023")
     *
     * **validAt values (for relationship):**
     * - `"always"` → relationship was always valid (ALWAYS_VALID_FROM sentinel)
     * - `"now"` → relationship valid from current moment (Clock.System.now())
     * - ISO 8601 timestamp → relationship valid from specific date
     * - `null` → ERROR (explicit value required)
     *
     * **invalidAt values (for relationship):**
     * - `"always"` → relationship valid forever (STILL_VALID sentinel)
     * - `"now"` → relationship becomes invalid now (Clock.System.now())
     * - ISO 8601 timestamp → relationship valid until specific date
     * - `null` → ERROR (explicit value required)
     *
     * **Examples:**
     * - Timeless fact: `validAt="always", invalidAt="always"`
     * - Current fact: `validAt="now", invalidAt="always"`
     * - Historical fact: `validAt="2020-01-01T00:00:00Z", invalidAt="always"`
     * - Limited period: `validAt="2020-01-01", invalidAt="2023-01-01"`
     *
     * **Note:** For temporal entities (e.g., "COVID-19 pandemic" valid 2020-2023),
     * use a dedicated tool (not yet implemented).
     *
     * @param from source entity name (e.g., "Gromozeka")
     * @param relation relationship description (e.g., "written in", "uses")
     * @param to target entity name (e.g., "Kotlin")
     * @param summary optional summary for source entity (updates existing if provided)
     * @param validAt when RELATIONSHIP became valid ("always", "now", or ISO 8601 - REQUIRED)
     * @param invalidAt when RELATIONSHIP became invalid ("always", "now", or ISO 8601 - REQUIRED)
     * @return human-readable success message with created fact, or error message if failed
     *
     * @see invalidateFact for removing/updating facts
     */
    suspend fun addFactDirectly(
        from: String,
        relation: String,
        to: String,
        summary: String? = null,
        validAt: String? = null,
        invalidAt: String? = null
    ): String

    /**
     * Retrieves detailed information about an entity.
     *
     * **Tool exposure:** `get_memory_object`
     *
     * Returns multi-line formatted text with:
     * - Entity name, type, summary, creation time
     * - Outgoing relationships (entity -> relation -> target)
     * - Incoming relationships (source -> relation -> entity)
     *
     * Example output:
     * ```
     * Entity: Gromozeka
     * Type: Technology
     * Summary: Multi-armed AI agent...
     * Created: 2024-01-15T10:30:00Z
     *
     * Outgoing relationships:
     *   - written in -> Kotlin
     *   - uses -> Spring AI
     *
     * Incoming relationships:
     *   - lewik -> created
     * ```
     *
     * Only shows currently valid relationships (WHERE invalid_at IS NULL).
     *
     * @param name entity name to lookup
     * @return formatted entity details string, or "Entity not found" message
     */
    suspend fun getEntityDetails(name: String): String

    /**
     * Marks a fact as invalid (soft delete with bi-temporal tracking).
     *
     * **Tool exposure:** `invalidate_memory_link`
     *
     * Sets invalidAt timestamp on matching relationship, preserving history.
     * Fact remains in database but filtered out from queries (WHERE invalid_at IS NULL).
     *
     * Finds relationship by:
     * - Source entity name = [from]
     * - Relationship description = [relation]
     * - Target entity name = [to]
     * - Currently valid (invalid_at IS NULL)
     *
     * Use case: Correcting outdated/wrong information without losing history.
     * Example: User says "No, Gromozeka is NOT written in Java" → invalidate wrong fact
     *
     * @param from source entity name
     * @param relation relationship description (exact match)
     * @param to target entity name
     * @return success message with invalidation timestamp, or "No matching fact found" if not exists
     *
     * @see addFactDirectly for adding corrected fact after invalidation
     */
    suspend fun invalidateFact(
        from: String,
        relation: String,
        to: String
    ): String

    /**
     * Updates entity summary and/or type.
     *
     * **Tool exposure:** `update_memory_object`
     *
     * Modifies existing entity properties in-place (not versioned).
     * At least one of [newSummary] or [newType] must be provided.
     *
     * @param name entity name to update
     * @param newSummary new summary text (null = don't update)
     * @param newType new entity type label (null = don't update)
     * @return success message listing updated fields, or "Entity not found" if doesn't exist
     *
     * Example: `updateEntity("Gromozeka", newSummary = "...additional details...", newType = "Project")`
     */
    suspend fun updateEntity(
        name: String,
        newSummary: String? = null,
        newType: String? = null
    ): String

    /**
     * ⚠️ PERMANENTLY deletes entity from knowledge graph (NO UNDO!)
     *
     * **Tool exposure:** `delete_memory_object`
     *
     * **DANGER: This is a hard delete** - entity and optionally relationships are destroyed.
     * No history preservation, no soft delete. Use with extreme caution.
     *
     * **Prefer [invalidateFact] for normal use** - preserves history and supports time-travel queries.
     *
     * Use cases for hard delete:
     * - Removing test/dummy data
     * - Cleaning up incorrectly created entities
     * - GDPR/privacy: purging sensitive data
     *
     * @param name entity name to permanently delete
     * @param cascade if true, deletes all relationships connected to entity; if false, only deletes entity node
     * @return multi-line status message with deletion counts and warning that operation cannot be undone
     *
     * Example output:
     * ```
     * ⚠️ PERMANENTLY DELETED entity 'TestEntity':
     * - Nodes deleted: 1
     * - Relationships deleted: 5
     *
     * This operation CANNOT be undone!
     * ```
     */
    suspend fun hardDeleteEntity(
        name: String,
        cascade: Boolean = true
    ): String
}
