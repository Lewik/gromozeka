package com.gromozeka.domain.service

import com.gromozeka.domain.model.memory.MemoryObject
import com.gromozeka.domain.model.memory.MemoryLink

/**
 * Service for knowledge graph operations.
 *
 * Manages entities and relationships in the organizational memory graph.
 * Infrastructure layer implements this using Neo4j or other graph databases.
 *
 * Knowledge graph enables relationship-based queries and context discovery.
 */
interface KnowledgeGraphService {
    /**
     * Creates or updates memory object (entity) in knowledge graph.
     *
     * If entity with same name exists, updates it.
     * This is a transactional operation.
     *
     * @param memoryObject entity to store
     * @return stored entity with timestamps
     */
    suspend fun saveMemoryObject(memoryObject: MemoryObject): MemoryObject

    /**
     * Creates relationship between two entities.
     *
     * Creates entities if they don't exist.
     * This is a transactional operation.
     *
     * @param link relationship to create
     * @return created relationship with timestamps
     */
    suspend fun saveMemoryLink(link: MemoryLink): MemoryLink

    /**
     * Finds entity by name.
     *
     * @param name entity name (case-sensitive)
     * @return entity if found, null otherwise
     */
    suspend fun findMemoryObject(name: String): MemoryObject?

    /**
     * Searches for entities and relationships by query.
     *
     * Uses graph traversal and semantic search to find relevant context.
     * Results include both entities and their relationships.
     *
     * @param query search query text
     * @param limit maximum number of results
     * @return list of relevant memory objects and links
     */
    suspend fun search(query: String, limit: Int = 10): SearchResult

    /**
     * Extracts entities and relationships from text and saves to knowledge graph.
     *
     * **Tool exposure:** `build_memory_from_text`
     *
     * ⚠️ **EXPENSIVE OPERATION - Use Sparingly:**
     * - Makes MULTIPLE LLM requests (one per entity + relationships)
     * - Expensive in tokens and time
     * - Use ONLY for large, complex texts with many entities
     * - For simple facts, use MemoryManagementService.addFactDirectly() instead (direct, no LLM parsing)
     * - ALWAYS ask user permission before using this tool
     *
     * **When to use:**
     * - ✅ Large documents with many interconnected concepts
     * - ✅ Complex technical explanations requiring entity extraction
     * - ❌ Simple facts like "X uses Y" (use addFactDirectly instead)
     * - ❌ Single relationships (use addFactDirectly instead)
     *
     * **How it works:**
     * - Uses LLM to extract entities, relationships, and generate embeddings
     * - Performs entity deduplication to avoid creating duplicate nodes
     * - This is a TRANSACTIONAL operation - creates all entities and relationships atomically
     *
     * @param content text content to extract from (conversation messages, documents, etc.)
     * @param previousMessages optional context from previous conversation
     * @return summary message with count of entities and relationships added
     *
     * @see com.gromozeka.domain.repository.MemoryManagementService.addFactDirectly for simple facts
     */
    suspend fun extractAndSaveToGraph(
        content: String,
        previousMessages: String = ""
    ): String

    /**
     * Result of knowledge graph search.
     *
     * @property objects entities matching the query
     * @property links relationships connecting matching entities
     */
    data class SearchResult(
        val objects: List<MemoryObject>,
        val links: List<MemoryLink>
    )
}
