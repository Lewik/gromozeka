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
 * **Why realistic dates (0001/9999) instead of Kotlin's DISTANT_PAST/DISTANT_FUTURE:**
 * - Neo4j datetime() function supports years 0001-9999 reliably
 * - Kotlin's DISTANT_PAST (year -292275055) causes Neo4j parsing errors
 * - Standard range for temporal databases (PostgreSQL, Oracle, SQL Server)
 * - Sufficient for all practical purposes (covers all human history and far future)
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
     * **Value:** `0001-01-01T00:00:00Z` (Year 1, start of Common Era)
     *
     * **Semantics:**
     * - Entity/relationship existed before we started tracking
     * - Timeless concept (e.g., "Kotlin", "Mathematics")
     * - No specific creation date in real world
     *
     * **Why Year 1:**
     * - Neo4j datetime() function reliably supports years 0001-9999
     * - Standard minimum for temporal databases (PostgreSQL, Oracle)
     * - Covers all recorded human history
     *
     * **Example:**
     * ```kotlin
     * MemoryObject(
     *     name = "Kotlin",
     *     validAt = ALWAYS_VALID_FROM  // Kotlin as concept always existed
     * )
     * ```
     */
    val ALWAYS_VALID_FROM: Instant = Instant.parse("0001-01-01T00:00:00Z")

    /**
     * Sentinel value for "still valid" (end of time).
     *
     * Use when entity/relationship has not been invalidated yet and should be
     * considered valid indefinitely into the future.
     *
     * **Value:** `9999-12-31T23:59:59Z` (Year 9999, practical infinity)
     *
     * **Semantics:**
     * - Entity/relationship is currently valid
     * - Not yet invalidated/deprecated/obsolete
     * - No known end date
     *
     * **Why Year 9999:**
     * - Neo4j datetime() function reliably supports years 0001-9999
     * - Standard maximum for temporal databases (PostgreSQL, Oracle, Teradata)
     * - Far enough in future for all practical purposes (8000 years from now)
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
    val STILL_VALID: Instant = Instant.parse("9999-12-31T23:59:59Z")
}
