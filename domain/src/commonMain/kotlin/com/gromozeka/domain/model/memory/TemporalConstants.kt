package com.gromozeka.domain.model.memory

import kotlinx.datetime.Instant

/**
 * Sentinel values for temporal fields in bi-temporal knowledge graph.
 *
 * Following industry best practices for temporal databases, we use sentinel values
 * instead of nullable fields to simplify queries and improve index performance.
 *
 * ## Rationale
 *
 * **Why NOT NULL with sentinel values:**
 * - Simpler queries (no NULL checks)
 * - Better index performance (Neo4j can index datetime fields efficiently)
 * - Type safety (no nullable types to handle)
 * - Semantic clarity (explicit constants vs ambiguous null)
 *
 * **Industry precedent:**
 * - PostgreSQL: `'infinity'` special value
 * - Oracle: NULL interpreted as +∞
 * - Teradata: `9999-12-31` as max date
 * - Graphiti (Zep AI): Uses sentinel values for temporal fields
 *
 * ## Usage
 *
 * ```kotlin
 * // Entity always valid (from beginning of time)
 * MemoryObject(
 *     validAt = TemporalConstants.ALWAYS_VALID_FROM,
 *     invalidAt = TemporalConstants.STILL_VALID
 * )
 *
 * // Entity valid from specific date, still valid
 * MemoryObject(
 *     validAt = Instant.parse("2020-01-01T00:00:00Z"),
 *     invalidAt = TemporalConstants.STILL_VALID
 * )
 *
 * // Entity with limited validity period
 * MemoryObject(
 *     validAt = Instant.parse("2020-01-01T00:00:00Z"),
 *     invalidAt = Instant.parse("2023-01-01T00:00:00Z")
 * )
 * ```
 *
 * ## Temporal Queries
 *
 * ```cypher
 * // Simple query - no NULL checks needed!
 * MATCH (a)-[r:LINKS_TO]->(b)
 * WHERE datetime(r.valid_at) <= datetime($asOf)
 *   AND datetime(r.invalid_at) > datetime($asOf)
 * RETURN a.name, r.description, b.name
 * ```
 */
object TemporalConstants {
    /**
     * Sentinel value for "always valid from" (beginning of time).
     *
     * Use when entity/relationship has no start date and should be considered
     * valid from the beginning of recorded history.
     *
     * **Value:** `-292275055-05-16T16:47:04.192Z` (Kotlin's Instant.DISTANT_PAST)
     *
     * **Semantics:**
     * - Entity/relationship existed before we started tracking
     * - Timeless concept (e.g., "Kotlin", "Mathematics")
     * - No specific creation date in real world
     *
     * **Example:**
     * ```kotlin
     * MemoryObject(
     *     name = "Kotlin",
     *     validAt = ALWAYS_VALID_FROM  // Kotlin as concept always existed
     * )
     * ```
     */
    val ALWAYS_VALID_FROM: Instant = Instant.DISTANT_PAST

    /**
     * Sentinel value for "still valid" (end of time).
     *
     * Use when entity/relationship has not been invalidated yet and should be
     * considered valid indefinitely into the future.
     *
     * **Value:** `+292278994-08-17T07:12:55.807Z` (Kotlin's Instant.DISTANT_FUTURE)
     *
     * **Semantics:**
     * - Entity/relationship is currently valid
     * - Not yet invalidated/deprecated/obsolete
     * - No known end date
     *
     * **Example:**
     * ```kotlin
     * MemoryObject(
     *     name = "Project X uses Neo4j",
     *     validAt = Clock.System.now(),
     *     invalidAt = STILL_VALID  // Still using Neo4j
     * )
     * ```
     */
    val STILL_VALID: Instant = Instant.DISTANT_FUTURE
}
