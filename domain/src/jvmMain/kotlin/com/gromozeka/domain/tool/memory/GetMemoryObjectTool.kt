package com.gromozeka.domain.tool.memory

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for get_memory_object tool.
 * 
 * @property name Entity name to look up (required)
 */
data class GetMemoryObjectRequest(
    val name: String
)

/**
 * Domain specification for entity retrieval from knowledge graph.
 * 
 * # MCP Tool Exposure
 * 
 * **Tool name:** `get_memory_object`
 * 
 * **Exposed via:** Spring AI + MCP protocol
 * 
 * # Purpose
 * 
 * Retrieve detailed information about a specific entity in the knowledge graph.
 * Returns entity metadata, relationships, and related entities.
 * 
 * ## Core Features
 * 
 * **Entity details:**
 * - Summary (entity description)
 * - Type (Person, Technology, Concept, Organization, etc.)
 * - Creation timestamp
 * - Last updated timestamp
 * 
 * **Relationship information:**
 * - All outgoing relationships (entity → other)
 * - All incoming relationships (other → entity)
 * - Relationship types and targets
 * - Relationship timestamps (created_at, invalid_at)
 * 
 * **Related entities:**
 * - Direct neighbors in knowledge graph
 * - Complete relationship context
 * 
 * # When to Use
 * 
 * **Use get_memory_object when:**
 * - User asks: "What do we know about X?"
 * - Before updating entity (check current state)
 * - Verifying entity was created correctly
 * - Debugging knowledge graph structure
 * - Exploring entity relationships
 * 
 * **Don't use when:**
 * - Searching for entities by content → use vector/semantic search
 * - Querying multiple entities → iterate or use graph query
 * - Creating new entity → use `add_memory_link`
 * - Entity doesn't exist → returns error, not empty result
 * 
 * # Parameters
 * 
 * ## name: String (required)
 * 
 * Entity name to look up (exact match, case-sensitive).
 * 
 * **Examples:**
 * - `"Gromozeka"` - Project name
 * - `"Spring AI"` - Technology
 * - `"Alice"` - Person
 * - `"ThreadRepository"` - Code entity
 * 
 * **Validation:**
 * - Must be exact entity name (case-sensitive)
 * - No fuzzy matching (use search for that)
 * - Returns error if entity doesn't exist
 * 
 * # Returns
 * 
 * Returns `Map<String, Any>` with entity details:
 * 
 * ## Success Response
 * 
 * ```json
 * {
 *   "type": "text",
 *   "text": "📋 Entity: Gromozeka\n\n**Type:** Technology\n\n**Summary:**\nMulti-agent AI assistant with hybrid memory architecture\n\n**Created:** 2025-11-20T14:30:00Z\n\n**Outgoing Relationships (5):**\n- written in → Kotlin (created: 2025-11-20T14:30:00Z)\n- uses → Spring AI (created: 2025-11-20T14:31:00Z)\n- uses → Neo4j (created: 2025-11-20T14:31:00Z)\n- uses → Qdrant (created: 2025-11-20T14:31:00Z)\n- follows → Clean Architecture (created: 2025-11-21T09:00:00Z)\n\n**Incoming Relationships (2):**\n- Architect Agent → created → Gromozeka (created: 2025-11-20T14:30:00Z)\n- Repository Agent → contributes to → Gromozeka (created: 2025-11-21T10:15:00Z)\n\n**Related Entities:** Kotlin, Spring AI, Neo4j, Qdrant, Clean Architecture, Architect Agent, Repository Agent"
 * }
 * ```
 * 
 * **Response contains:**
 * - Entity name and type
 * - Summary description
 * - Creation timestamp
 * - Outgoing relationships (this entity → others)
 * - Incoming relationships (others → this entity)
 * - List of all related entities
 * 
 * ## Error Response
 * 
 * ```json
 * {
 *   "type": "text",
 *   "text": "Error getting entity details: Entity 'UnknownEntity' not found in knowledge graph"
 * }
 * ```
 * 
 * # Error Cases
 * 
 * | Error | Reason | Solution |
 * |-------|--------|----------|
 * | Empty name | `name` is blank | Provide entity name |
 * | Entity not found | Entity doesn't exist | Check spelling, use search to find similar |
 * | Neo4j error | Database unavailable | Check Neo4j service |
 * 
 * # Usage Examples
 * 
 * ## Example 1: Check What We Know
 * 
 * **Use case:** User asks "What do we know about Gromozeka?"
 * 
 * ```json
 * {
 *   "tool": "get_memory_object",
 *   "parameters": {
 *     "name": "Gromozeka"
 *   }
 * }
 * ```
 * 
 * **Result:** Complete entity details with all relationships
 * 
 * ## Example 2: Verify Entity Creation
 * 
 * **Use case:** After `add_memory_link`, verify it worked
 * 
 * ```json
 * {
 *   "tool": "get_memory_object",
 *   "parameters": {
 *     "name": "ThreadRepository"
 *   }
 * }
 * ```
 * 
 * **Result:** Confirms entity exists and shows its relationships
 * 
 * ## Example 3: Before Updating
 * 
 * **Use case:** Check current state before modifying
 * 
 * 1. `get_memory_object("Spring AI")` - See current summary
 * 2. Decide what to update
 * 3. `update_memory_object("Spring AI", newSummary="...")` - Update
 * 
 * **Result:** Informed update based on current state
 * 
 * ## Example 4: Explore Relationships
 * 
 * **Use case:** Understand entity's role in system
 * 
 * ```json
 * {
 *   "tool": "get_memory_object",
 *   "parameters": {
 *     "name": "Neo4j"
 *   }
 * }
 * ```
 * 
 * **Result:** Shows what uses Neo4j and what Neo4j is used for
 * 
 * # Common Patterns
 * 
 * ## Pattern: Incremental Exploration
 * 
 * Navigate knowledge graph by following relationships:
 * 
 * 1. `get_memory_object("Gromozeka")` - Start at main entity
 * 2. See relationship: "uses → Spring AI"
 * 3. `get_memory_object("Spring AI")` - Explore related entity
 * 4. Continue following relationships
 * 
 * Result: Deep understanding of entity network
 * 
 * ## Pattern: Verify Before Delete
 * 
 * Check what will be affected before deletion:
 * 
 * 1. `get_memory_object("EntityToDelete")` - See relationships
 * 2. Count incoming/outgoing relationships
 * 3. Decide if safe to delete
 * 4. `delete_memory_object("EntityToDelete", cascade=true)` - If confirmed
 * 
 * Result: Informed deletion decision
 * 
 * ## Pattern: Relationship Audit
 * 
 * Find all connections for specific entity:
 * 
 * 1. `get_memory_object("Technology")` - Get entity
 * 2. Count outgoing relationships (dependencies)
 * 3. Count incoming relationships (dependents)
 * 4. Analyze relationship patterns
 * 
 * Result: Complete dependency map
 * 
 * # Transactionality
 * 
 * **Read-only operation:**
 * - No side effects
 * - Safe to call multiple times
 * - No state changes
 * 
 * **Concurrency:**
 * - Safe for concurrent reads
 * - Snapshot consistency (sees committed relationships)
 * 
 * **Idempotency:**
 * - Strictly idempotent (same input → same output)
 * - Can be cached
 * 
 * # Performance Characteristics
 * 
 * - **Execution time:** <50ms (single graph query)
 * - **Scalability:** Degrades with high-degree nodes (100+ relationships)
 * - **Caching:** Results can be cached (entities change infrequently)
 * 
 * **High-degree nodes:**
 * - Entity with 1000+ relationships may take 100-500ms
 * - Consider pagination for very connected entities
 * 
 * # Relationship Timestamps
 * 
 * **created_at:**
 * - When relationship was created
 * - Never changes
 * - Used for temporal queries ("What was true on date X?")
 * 
 * **invalid_at:**
 * - When relationship was invalidated (soft delete)
 * - Null = still valid
 * - Set by `invalidate_memory_link`
 * 
 * **Filtering:**
 * - Only shows currently valid relationships (invalid_at = null)
 * - For historical queries, need separate tool/API
 * 
 * # Related Tools
 * 
 * - **add_memory_link** - Create entity and relationships
 * - **build_memory_from_text** - Extract entities from text
 * - **update_memory_object** - Modify entity metadata
 * - **invalidate_memory_link** - Mark relationship as outdated
 * - **delete_memory_object** - Permanently delete entity
 * 
 * # Infrastructure Implementation
 * 
 * See infrastructure-ai module for Spring AI integration:
 * @see com.gromozeka.infrastructure.ai.tool.memory.GetMemoryObjectTool
 * 
 * # Related Domain Services
 * 
 * This tool delegates to:
 * @see com.gromozeka.domain.service.MemoryManagementService.getEntityDetails
 */
interface GetMemoryObjectTool : Tool<GetMemoryObjectRequest, Map<String, Any>> {
    
    override val name: String
        get() = "get_memory_object"
    
    override val description: String
        get() = """
            Get detailed information about a specific entity in the knowledge graph.

            **Returns:**
            - Entity summary and type
            - All outgoing relationships (entity -> other)
            - All incoming relationships (other -> entity)
            - Creation timestamp
            - Related entities

            **Parameters:**
            - name: Entity name to look up (required)

            **Use Cases:**
            - Before updating: check what information exists
            - User asks: "What do we know about X?"
            - Debug: verify entity was created correctly
        """.trimIndent()
    
    override val requestType: Class<GetMemoryObjectRequest>
        get() = GetMemoryObjectRequest::class.java
    
    override fun execute(request: GetMemoryObjectRequest, context: ToolContext?): Map<String, Any>
}
