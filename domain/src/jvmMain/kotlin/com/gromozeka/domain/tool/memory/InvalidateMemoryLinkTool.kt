package com.gromozeka.domain.tool.memory

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for invalidate_memory_link tool.
 * 
 * @property from Source entity name
 * @property relation Relationship type
 * @property to Target entity name
 */
data class InvalidateMemoryLinkRequest(
    val from: String,
    val relation: String,
    val to: String
)

/**
 * Domain specification for relationship invalidation in knowledge graph.
 * 
 * # MCP Tool Exposure
 * 
 * **Tool name:** `invalidate_memory_link`
 * 
 * **Exposed via:** Spring AI + MCP protocol
 * 
 * # Purpose
 * 
 * Mark a fact (relationship) as outdated or invalid without deleting it from the knowledge graph.
 * Preserves historical information while updating current state.
 * 
 * ## Core Features
 * 
 * **Soft delete (bi-temporal model):**
 * - Sets `invalid_at` timestamp to current time
 * - Relationship remains in database for historical queries
 * - Future queries exclude invalidated facts
 * - Can query historical state: "What was true on date X?"
 * 
 * **History preservation:**
 * - Original `created_at` timestamp preserved
 * - Relationship data not modified
 * - Can analyze when facts changed over time
 * - Supports temporal reasoning
 * 
 * **Non-destructive:**
 * - Entities remain intact
 * - Other relationships unaffected
 * - Can be queried for historical analysis
 * - No data loss (unlike delete)
 * 
 * # When to Use
 * 
 * **Use invalidate_memory_link when:**
 * - User corrects: "No, Gromozeka is NOT written in Java"
 * - Outdated info: "We no longer use PostgreSQL"
 * - Mistake correction: invalidate wrong fact, add correct one
 * - Technology migration: old "uses X" → new "uses Y"
 * - Temporal changes: facts that were true but aren't anymore
 * 
 * **Don't use when:**
 * - Permanent deletion needed → use `delete_memory_object` (DANGER)
 * - Updating entity metadata → use `update_memory_object`
 * - Adding new fact → use `add_memory_link`
 * - Fact never existed → no need to invalidate
 * 
 * # Parameters
 * 
 * ## from: String (required)
 * 
 * Source entity name (subject of the relationship to invalidate).
 * 
 * **Examples:**
 * - `"Gromozeka"` - Project entity
 * - `"UserService"` - Code entity
 * - `"Architecture"` - Concept entity
 * 
 * **Validation:**
 * - Must exist in knowledge graph
 * - Exact name match (case-sensitive)
 * - Cannot be empty
 * 
 * ## relation: String (required)
 * 
 * Relationship type to invalidate.
 * 
 * **Common relations to invalidate:**
 * - `"written in"` - Changed programming language
 * - `"uses"` - No longer uses technology
 * - `"depends on"` - Dependency removed
 * - `"located in"` - Moved to different location
 * 
 * **Validation:**
 * - Must match existing relationship exactly
 * - Case-sensitive
 * - Cannot be empty
 * 
 * ## to: String (required)
 * 
 * Target entity name (object of the relationship to invalidate).
 * 
 * **Examples:**
 * - `"Java"` - Old technology
 * - `"PostgreSQL"` - Replaced database
 * - `"src/old/"` - Old location
 * 
 * **Validation:**
 * - Must exist in knowledge graph
 * - Exact name match (case-sensitive)
 * - Cannot be empty
 * 
 * # Returns
 * 
 * Returns `Map<String, Any>` with invalidation confirmation:
 * 
 * ## Success Response
 * 
 * ```json
 * {
 *   "type": "text",
 *   "text": "✅ Invalidated fact: Gromozeka -[written in]-> Java\n\nDetails:\n- Relationship existed from: 2025-11-15T10:00:00Z\n- Invalidated at: 2025-11-27T16:30:00Z\n- Duration: 12 days\n\nHistorical queries will still return this fact for dates before 2025-11-27.\nCurrent queries will exclude this fact."
 * }
 * ```
 * 
 * **Response contains:**
 * - Confirmation of invalidated fact
 * - When relationship was created
 * - When relationship was invalidated
 * - How long fact was valid
 * - Note about historical query behavior
 * 
 * ## Error Response
 * 
 * ```json
 * {
 *   "type": "text",
 *   "text": "Error invalidating fact: Relationship 'Gromozeka -[written in]-> Java' not found in knowledge graph"
 * }
 * ```
 * 
 * # Error Cases
 * 
 * | Error | Reason | Solution |
 * |-------|--------|----------|
 * | Empty parameters | from, relation, or to is blank | Provide all three parameters |
 * | Relationship not found | Fact doesn't exist | Check spelling, entity names, relation type |
 * | Already invalidated | Fact was already invalidated | No action needed (idempotent) |
 * | Neo4j error | Database unavailable | Check Neo4j service |
 * 
 * # Usage Examples
 * 
 * ## Example 1: Correct Technology Stack
 * 
 * **Use case:** User corrects: "Actually, we use Kotlin not Java"
 * 
 * ```json
 * [
 *   {
 *     "tool": "invalidate_memory_link",
 *     "parameters": {
 *       "from": "Gromozeka",
 *       "relation": "written in",
 *       "to": "Java"
 *     }
 *   },
 *   {
 *     "tool": "add_memory_link",
 *     "parameters": {
 *       "from": "Gromozeka",
 *       "relation": "written in",
 *       "to": "Kotlin"
 *     }
 *   }
 * ]
 * ```
 * 
 * **Result:** Old fact invalidated, correct fact added, history preserved
 * 
 * ## Example 2: Technology Migration
 * 
 * **Use case:** Migrated from PostgreSQL to Neo4j
 * 
 * ```json
 * [
 *   {
 *     "tool": "invalidate_memory_link",
 *     "parameters": {
 *       "from": "Gromozeka",
 *       "relation": "uses",
 *       "to": "PostgreSQL"
 *     }
 *   },
 *   {
 *     "tool": "add_memory_link",
 *     "parameters": {
 *       "from": "Gromozeka",
 *       "relation": "uses",
 *       "to": "Neo4j"
 *     }
 *   }
 * ]
 * ```
 * 
 * **Result:** Migration tracked with dates, can query "When did we migrate?"
 * 
 * ## Example 3: Fix Extraction Mistake
 * 
 * **Use case:** LLM extracted wrong relationship
 * 
 * ```json
 * {
 *   "tool": "invalidate_memory_link",
 *   "parameters": {
 *     "from": "ThreadRepository",
 *     "relation": "depends on",
 *     "to": "WrongDependency"
 *   }
 * }
 * ```
 * 
 * **Result:** Incorrect relationship invalidated, can add correct one
 * 
 * ## Example 4: Remove Outdated Dependency
 * 
 * **Use case:** Removed library from project
 * 
 * ```json
 * {
 *   "tool": "invalidate_memory_link",
 *   "parameters": {
 *     "from": "Project",
 *     "relation": "depends on",
 *     "to": "OldLibrary"
 *   }
 * }
 * ```
 * 
 * **Result:** Dependency marked as removed, history shows when it was used
 * 
 * # Common Patterns
 * 
 * ## Pattern: Correct Then Replace
 * 
 * Standard pattern for fixing mistakes:
 * 
 * 1. `invalidate_memory_link(from, relation, wrongTo)` - Mark wrong fact invalid
 * 2. `add_memory_link(from, relation, correctTo)` - Add correct fact
 * 
 * Result: History shows mistake was made, current state is correct
 * 
 * ## Pattern: Technology Evolution Tracking
 * 
 * Document technology changes over time:
 * 
 * 1. Initially: `add_memory_link("Project", "uses", "TechV1")`
 * 2. Migration: `invalidate_memory_link("Project", "uses", "TechV1")`
 * 3. New version: `add_memory_link("Project", "uses", "TechV2")`
 * 4. Query: "When did we migrate from TechV1 to TechV2?"
 * 
 * Result: Complete migration timeline preserved
 * 
 * ## Pattern: Batch Cleanup
 * 
 * Remove multiple outdated facts:
 * 
 * ```kotlin
 * for (tech in oldTechnologies) {
 *     invalidate_memory_link("Project", "uses", tech)
 * }
 * for (tech in newTechnologies) {
 *     add_memory_link("Project", "uses", tech)
 * }
 * ```
 * 
 * Result: Tech stack updated, history preserved
 * 
 * # Transactionality
 * 
 * **Single transaction:**
 * - Invalidation is atomic (all or nothing)
 * - Sets `invalid_at` timestamp atomically
 * - No partial state
 * 
 * **Idempotency:**
 * - Idempotent (invalidating already invalid fact = no change)
 * - Safe to call multiple times
 * - Last invalidation timestamp wins
 * 
 * **Concurrency:**
 * - Safe for concurrent invalidations
 * - Multiple invalidations of same fact = last timestamp wins
 * - No conflict issues
 * 
 * # Performance Characteristics
 * 
 * - **Execution time:** <20ms (single relationship update)
 * - **Scalability:** Constant time (doesn't depend on entity size)
 * - **Token cost:** 0 (no LLM calls)
 * 
 * **Comparison to delete:**
 * - invalidate_memory_link: instant, preserves history
 * - delete_memory_object: instant, loses history permanently
 * 
 * **Recommendation:** Always prefer invalidation over deletion unless you need to purge data.
 * 
 * # Bi-Temporal Model Deep Dive
 * 
 * **Valid time (business time):**
 * - `created_at`: When fact became true in real world
 * - `invalid_at`: When fact stopped being true in real world
 * - null `invalid_at` = still true
 * 
 * **Transaction time (system time):**
 * - When fact was recorded in database
 * - When fact was invalidated in database
 * - Tracks knowledge evolution
 * 
 * **Temporal queries supported:**
 * - "What did we use on 2025-01-01?" → facts valid on that date
 * - "When did we start using X?" → query `created_at`
 * - "When did we stop using X?" → query `invalid_at`
 * - "How long did we use X?" → `invalid_at` - `created_at`
 * 
 * # Invalidation vs Deletion
 * 
 * | Feature | invalidate_memory_link | delete_memory_object |
 * |---------|----------------------|---------------------|
 * | History | Preserved | Lost forever |
 * | Reversible | Yes (query historical) | No (permanent) |
 * | Use case | Mistake correction | Data purge |
 * | Safety | Safe | DANGEROUS |
 * | Temporal queries | Supported | Not possible |
 * | Storage | Uses disk space | Frees disk space |
 * 
 * **Default choice:** Use invalidation. Only delete for test data cleanup or data purge.
 * 
 * # Related Tools
 * 
 * - **add_memory_link** - Create new fact (often used after invalidation)
 * - **get_memory_object** - Check entity relationships before invalidating
 * - **update_memory_object** - Change entity metadata (not relationships)
 * - **delete_memory_object** - Permanently delete (DANGER, use invalidation instead)
 * 
 * # Infrastructure Implementation
 * 
 * See infrastructure-ai module for Spring AI integration:
 * @see com.gromozeka.infrastructure.ai.tool.memory.InvalidateMemoryLinkTool
 * 
 * # Related Domain Services
 * 
 * This tool delegates to:
 * @see com.gromozeka.domain.service.MemoryManagementService.invalidateFact
 */
interface InvalidateMemoryLinkTool : Tool<InvalidateMemoryLinkRequest, Map<String, Any>> {
    
    override val name: String
        get() = "invalidate_memory_link"
    
    override val description: String
        get() = """
            Mark a fact as outdated/invalid without deleting it (preserves history).
            Use this when correcting mistakes or updating information.

            **Bi-temporal Model:**
            - Sets invalid_at = current_timestamp
            - Fact remains in database for history
            - Future searches exclude invalidated facts
            - Can query historical state: "What was true on date X?"

            **Parameters:**
            - from: Source entity name
            - relation: Relationship type
            - to: Target entity name

            **Use Cases:**
            - User corrects: "No, Gromozeka is NOT written in Java"
            - Outdated info: "We no longer use PostgreSQL"
            - Mistake correction: invalidate wrong fact, add correct one

            **Returns:** Confirmation message
        """.trimIndent()
    
    override val requestType: Class<InvalidateMemoryLinkRequest>
        get() = InvalidateMemoryLinkRequest::class.java
    
    override fun execute(request: InvalidateMemoryLinkRequest, context: ToolContext?): Map<String, Any>
}
