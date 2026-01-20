package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.memory.MemoryLink
import com.gromozeka.domain.model.memory.MemoryObject

/**
 * Repository for persisting and querying knowledge graph entities and relationships.
 *
 * Provides high-level operations for graph manipulation plus low-level query access
 * for complex graph traversals not covered by standard methods.
 *
 * ## Implementation Note
 * Current implementation uses Neo4j graph database. The [executeQuery] method exposes
 * Neo4j Cypher query language for flexibility. This is a pragmatic trade-off between
 * abstraction purity and real-world usability for complex graph operations.
 *
 * ## Multi-tenancy
 * All operations are scoped by groupId in [MemoryObject] and [MemoryLink].
 * Queries should filter by group_id to maintain tenant isolation.
 */
interface KnowledgeGraphStore {

    /**
     * Persists entity nodes to the graph.
     *
     * Uses MERGE semantics - creates new entities or updates existing ones based on UUID.
     * This is a transactional operation.
     *
     * @param entities entities to save
     * @throws IllegalStateException if graph database is unavailable
     */
    suspend fun saveEntities(entities: List<MemoryObject>)

    /**
     * Persists relationship edges to the graph.
     *
     * Requires source and target entities to exist (will fail if nodes not found).
     * Uses MERGE semantics - creates new relationships or updates existing ones based on UUID.
     * This is a transactional operation.
     *
     * @param relationships relationships to save
     * @throws IllegalStateException if source or target entities not found
     * @throws IllegalStateException if graph database is unavailable
     */
    suspend fun saveRelationships(relationships: List<MemoryLink>)

    /**
     * Saves both entities and relationships in a single operation.
     *
     * Convenience method equivalent to calling [saveEntities] then [saveRelationships].
     * Entities must be saved before relationships.
     *
     * @param entities entities to save
     * @param relationships relationships to save (must reference entities from first parameter)
     * @throws IllegalStateException if relationships reference non-existent entities
     * @throws IllegalStateException if graph database is unavailable
     */
    suspend fun saveToGraph(
        entities: List<MemoryObject>,
        relationships: List<MemoryLink>
    )

    /**
     * Finds entity by exact name match within group.
     *
     * Case-sensitive exact match on entity name and groupId.
     *
     * @param name exact entity name to search
     * @param groupId tenant identifier for isolation
     * @return matching entity or null if not found
     */
    suspend fun findEntityByName(name: String, groupId: String): MemoryObject?

    /**
     * Finds entity by unique identifier.
     *
     * @param uuid entity UUID
     * @return matching entity or null if not found
     */
    suspend fun findEntityByUuid(uuid: String): MemoryObject?

    /**
     * Executes arbitrary graph query with parameters.
     *
     * ⚠️ **WARNING: Technology-specific escape hatch**
     *
     * This method exposes Neo4j Cypher query language directly, violating
     * technology-agnostic domain design. This is a pragmatic compromise for:
     * - Complex graph traversals (multi-hop paths, pattern matching)
     * - Advanced aggregations not covered by standard methods
     * - Performance-critical custom queries
     *
     * **Use sparingly** - prefer standard methods when possible.
     * If you find yourself writing many custom queries, consider adding
     * new domain-specific methods to this interface instead.
     *
     * @param cypher Neo4j Cypher query string (parameterized with $param syntax)
     * @param params query parameters (matched by name to $param placeholders)
     * @return list of result records as string-keyed maps
     * @throws IllegalArgumentException if query syntax is invalid
     * @throws IllegalStateException if graph database is unavailable
     */
    suspend fun executeQuery(
        cypher: String,
        params: Map<String, Any> = emptyMap()
    ): List<Map<String, Any>>

    /**
     * Synchronizes Project entity to knowledge graph.
     *
     * Creates minimal Project node for code spec scoping and search result display.
     * Project metadata is denormalized from SQL to Neo4j to enable project-scoped
     * code spec filtering without joining across databases.
     *
     * **Stored in Neo4j:**
     * - `uuid` - Neo4j internal identifier (generated once, immutable)
     * - `project_id` - Domain Project.Id (MERGE key, used for filtering)
     * - `name` - Project name for search result display
     * - `group_id` - Multi-tenancy identifier
     *
     * **NOT stored (SQL-only):**
     * - `path` - derivable from code spec file paths, not needed for filtering
     * - `description` - not used in code spec search
     * - `favorite`, `archived` - UI preferences, not search criteria
     * - `createdAt`, `lastUsedAt` - not needed for code search
     *
     * **MERGE semantics:**
     * Matches by project_id, updates name and group_id if changed.
     *
     * **Graph structure:**
     * ```cypher
     * MERGE (p:Project {project_id: $projectId})
     * SET p.uuid = COALESCE(p.uuid, randomUUID()),
     *     p.name = $name,
     *     p.group_id = $groupId
     * RETURN p
     * ```
     *
     * **Called from:**
     * - IndexDomainToGraphTool - before indexing code specs
     * - ProjectDomainService - on project creation (future: eager sync)
     *
     * @param project complete Project entity (only id, name extracted; groupId from context)
     * @throws IllegalStateException if graph database is unavailable
     */
    suspend fun syncProject(project: Project)

    /**
     * Deletes all code specs belonging to specific project.
     *
     * Used before re-indexing to remove stale code specs and prevent duplicates.
     * Only removes entities with CodeSpec-related labels:
     * - DomainInterface, DomainClass, DomainFunction, DomainProperty, DomainConstructor, DomainEnum
     *
     * **Does NOT remove:**
     * - Memory objects (facts, concepts, technologies)
     * - Conversation messages
     * - Project entity itself
     * - Entities from other projects
     *
     * **Deletion strategy:**
     * ```cypher
     * MATCH (spec)-[:BELONGS_TO_PROJECT]->(p:Project {project_id: $projectId})
     * WHERE spec:DomainInterface OR spec:DomainClass OR ...
     * DETACH DELETE spec
     * ```
     *
     * **Transactionality:**
     * - Atomic operation - all code specs deleted or none
     * - Cascades to relationships (DETACH DELETE)
     *
     * **Use cases:**
     * - Before re-indexing project (clean slate)
     * - When project structure changes significantly
     * - Manual cleanup of outdated code specs
     *
     * @param projectId domain Project.Id to filter code specs
     * @return count of deleted code spec entities
     * @throws IllegalStateException if graph database is unavailable
     */
    suspend fun deleteCodeSpecsByProject(projectId: String): Int

    /**
     * Finds Project entity by project ID.
     *
     * Returns Project node as MemoryObject for consistency with other graph entities.
     *
     * **Labels:**
     * - `["Project"]`
     *
     * **Attributes:**
     * - `project_id` - domain Project.Id
     * - `name` - project name
     * - `group_id` - multi-tenancy identifier
     *
     * @param projectId domain Project.Id
     * @return Project node as MemoryObject or null if not found
     */
    suspend fun findProjectEntity(projectId: String): MemoryObject?
}
