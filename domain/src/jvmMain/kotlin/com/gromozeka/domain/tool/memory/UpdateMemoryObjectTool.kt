package com.gromozeka.domain.tool.memory

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for update_memory_object tool.
 * 
 * @property name Entity name to update (required)
 * @property newSummary New summary text (optional, null = no change)
 * @property newType New entity type (optional, null = no change)
 */
data class UpdateMemoryObjectRequest(
    val name: String,
    val newSummary: String? = null,
    val newType: String? = null
)

/**
 * Domain specification for entity metadata updates in knowledge graph.
 * 
 * # MCP Tool Exposure
 * 
 * **Tool name:** `update_memory_object`
 * 
 * **Exposed via:** Spring AI + MCP protocol
 * 
 * # Purpose
 * 
 * Update an existing entity's summary or type without changing its relationships.
 * Allows refining entity descriptions and reclassifying entities.
 * 
 * ## Core Features
 * 
 * **Metadata updates:**
 * - Change entity summary (description)
 * - Change entity type (classification)
 * - Preserve all relationships
 * - Keep entity identity (name)
 * 
 * **Non-destructive:**
 * - Doesn't delete or invalidate relationships
 * - Doesn't change entity name
 * - Can update summary or type independently
 * - Idempotent (same update multiple times = same result)
 * 
 * **Versioning:**
 * - Updates `updated_at` timestamp
 * - Old values not preserved (overwrite)
 * - For historical tracking, manually record changes
 * 
 * # When to Use
 * 
 * **Use update_memory_object when:**
 * - User refines entity description: "Add to Gromozeka summary that it has voice interface"
 * - Reclassifying entity: "Actually, Spring AI is a Framework, not a Library"
 * - Enhancing incomplete summaries
 * - Correcting entity types
 * - Adding missing details to existing entity
 * 
 * **Don't use when:**
 * - Changing relationships → use `invalidate_memory_link` + `add_memory_link`
 * - Renaming entity → delete old, create new (no rename operation)
 * - Entity doesn't exist → use `add_memory_link` to create
 * - Want to track changes → manually add relationship "updated_by" etc.
 * 
 * # Parameters
 * 
 * ## name: String (required)
 * 
 * Entity name to update (exact match, case-sensitive).
 * 
 * **Examples:**
 * - `"Gromozeka"` - Project entity
 * - `"Spring AI"` - Technology entity
 * - `"ThreadRepository"` - Code entity
 * 
 * **Validation:**
 * - Must exist (error if not found)
 * - Exact name match (case-sensitive)
 * - Cannot be empty
 * 
 * ## newSummary: String? (optional)
 * 
 * New summary text to replace current summary.
 * 
 * **Behavior:**
 * - If provided: replaces entire summary (not append)
 * - If null: summary unchanged
 * - If empty string: clears summary
 * 
 * **Best practices:**
 * - Include all important information (overwrites, not appends)
 * - Keep concise (1-3 sentences)
 * - Focus on entity essence, not exhaustive details
 * - Use present tense for current facts
 * 
 * **Examples:**
 * ```
 * "Multi-agent AI assistant with hybrid memory architecture and voice interface"
 * "Graph database for knowledge graph storage with bi-temporal support"
 * "Repository for conversation thread persistence using Exposed ORM"
 * ```
 * 
 * ## newType: String? (optional)
 * 
 * New entity type classification.
 * 
 * **Common types:**
 * - `"Technology"` - Software, frameworks, languages
 * - `"Person"` - Individuals, agents, users
 * - `"Concept"` - Abstract ideas, patterns, principles
 * - `"Organization"` - Companies, teams, groups
 * - `"Code"` - Classes, methods, modules
 * - `"Document"` - Files, specs, documentation
 * 
 * **Behavior:**
 * - If provided: replaces current type
 * - If null: type unchanged
 * - Case-sensitive (recommend Title Case)
 * 
 * # Returns
 * 
 * Returns `Map<String, Any>` with update confirmation:
 * 
 * ## Success Response
 * 
 * ```json
 * {
 *   "type": "text",
 *   "text": "✅ Updated entity: Gromozeka\n\nChanges:\n- Summary: Updated\n  Old: Multi-agent AI assistant with hybrid memory architecture\n  New: Multi-agent AI assistant with hybrid memory architecture and voice interface\n\nType: Technology (unchanged)\n\nUpdated at: 2025-11-27T15:45:00Z"
 * }
 * ```
 * 
 * **Response contains:**
 * - Entity name
 * - Fields changed (Summary, Type, or both)
 * - Old → New values for changed fields
 * - Unchanged fields noted
 * - Update timestamp
 * 
 * ## Success Response (No Changes)
 * 
 * ```json
 * {
 *   "type": "text",
 *   "text": "ℹ️ Entity 'Gromozeka' unchanged (newSummary and newType are null)"
 * }
 * ```
 * 
 * ## Error Response
 * 
 * ```json
 * {
 *   "type": "text",
 *   "text": "Error updating entity: Entity 'UnknownEntity' not found in knowledge graph"
 * }
 * ```
 * 
 * # Error Cases
 * 
 * | Error | Reason | Solution |
 * |-------|--------|----------|
 * | Empty name | `name` is blank | Provide entity name |
 * | Entity not found | Entity doesn't exist | Create with `add_memory_link` first |
 * | No changes | Both newSummary and newType are null | Provide at least one field to update |
 * | Neo4j error | Database unavailable | Check Neo4j service |
 * 
 * # Usage Examples
 * 
 * ## Example 1: Enhance Summary
 * 
 * **Use case:** User adds detail to existing entity
 * 
 * ```json
 * {
 *   "tool": "update_memory_object",
 *   "parameters": {
 *     "name": "Gromozeka",
 *     "newSummary": "Multi-agent AI assistant with hybrid memory architecture, voice interface, and MCP tool integration"
 *   }
 * }
 * ```
 * 
 * **Result:** Summary updated with additional details
 * 
 * ## Example 2: Fix Entity Type
 * 
 * **Use case:** Entity was classified incorrectly
 * 
 * ```json
 * {
 *   "tool": "update_memory_object",
 *   "parameters": {
 *     "name": "Spring AI",
 *     "newType": "Framework"
 *   }
 * }
 * ```
 * 
 * **Result:** Type changed from "Library" to "Framework"
 * 
 * ## Example 3: Update Both Fields
 * 
 * **Use case:** Refine both summary and type
 * 
 * ```json
 * {
 *   "tool": "update_memory_object",
 *   "parameters": {
 *     "name": "ThreadRepository",
 *     "newSummary": "Repository for conversation thread persistence using Exposed ORM with Neo4j vector integration",
 *     "newType": "Code"
 *   }
 * }
 * ```
 * 
 * **Result:** Both summary and type updated
 * 
 * ## Example 4: Clear Summary
 * 
 * **Use case:** Remove auto-generated summary
 * 
 * ```json
 * {
 *   "tool": "update_memory_object",
 *   "parameters": {
 *     "name": "TemporaryEntity",
 *     "newSummary": ""
 *   }
 * }
 * ```
 * 
 * **Result:** Summary cleared (empty string)
 * 
 * # Common Patterns
 * 
 * ## Pattern: Incremental Enhancement
 * 
 * Build entity knowledge over time:
 * 
 * 1. Initial: `add_memory_link("Service", "uses", "DB")` - Minimal entity
 * 2. First update: `update_memory_object("Service", newSummary="User management service")`
 * 3. Second update: `update_memory_object("Service", newSummary="User management service with JWT authentication")`
 * 
 * Result: Entity description grows with understanding
 * 
 * ## Pattern: Fix After Extraction
 * 
 * Correct LLM extraction mistakes:
 * 
 * 1. `build_memory_from_text(...)` - LLM extracts entities
 * 2. `get_memory_object("Entity")` - Check what was created
 * 3. `update_memory_object("Entity", newSummary="...", newType="...")` - Fix errors
 * 
 * Result: Accurate entity metadata after automated extraction
 * 
 * ## Pattern: Reclassification
 * 
 * Adjust entity types as understanding improves:
 * 
 * 1. Initially classified as "Concept"
 * 2. Realize it's actually implemented technology
 * 3. `update_memory_object("Entity", newType="Technology")`
 * 
 * Result: Better entity categorization
 * 
 * # Transactionality
 * 
 * **Single transaction:**
 * - Update is atomic (all or nothing)
 * - Either both fields update or neither
 * - No partial state
 * 
 * **Idempotency:**
 * - Idempotent (same update multiple times = same result)
 * - Last update wins (no conflict resolution)
 * 
 * **Concurrency:**
 * - Concurrent updates may conflict
 * - Last write wins (no optimistic locking)
 * - Consider using timestamps for conflict detection
 * 
 * # Performance Characteristics
 * 
 * - **Execution time:** <20ms (single node update)
 * - **Scalability:** Constant time (doesn't depend on relationship count)
 * - **Token cost:** 0 (no LLM calls)
 * 
 * **Comparison:**
 * - update_memory_object: instant, deterministic
 * - Delete + recreate: slower, loses relationships
 * 
 * # Limitations
 * 
 * **Cannot rename:**
 * - Entity name is identity (cannot change)
 * - To rename: create new entity, copy relationships, delete old
 * 
 * **No history:**
 * - Old summary/type values lost (overwritten)
 * - For history tracking, manually add relationships ("previous_summary", etc.)
 * 
 * **No validation:**
 * - Doesn't validate summary content
 * - Doesn't enforce type constraints
 * - Any string accepted for both fields
 * 
 * # Related Tools
 * 
 * - **add_memory_link** - Create entity with initial summary
 * - **get_memory_object** - Check current entity state before updating
 * - **invalidate_memory_link** - Change relationships (not metadata)
 * - **delete_memory_object** - Remove entity entirely
 * 
 * # Infrastructure Implementation
 * 
 * See infrastructure-ai module for Spring AI integration:
 * @see com.gromozeka.infrastructure.ai.tool.memory.UpdateMemoryObjectTool
 * 
 * # Related Domain Services
 * 
 * This tool delegates to:
 * @see com.gromozeka.domain.service.MemoryManagementService.updateEntity
 */
interface UpdateMemoryObjectTool : Tool<UpdateMemoryObjectRequest, Map<String, Any>> {
    
    override val name: String
        get() = "update_memory_object"
    
    override val description: String
        get() = """
            Update an existing entity's summary or type in the knowledge graph.

            **What Can Be Updated:**
            - Summary: Refine or add details to entity description
            - Type: Change entity classification (e.g., Concept -> Technology)

            **Parameters:**
            - name: Entity name to update (required)
            - newSummary: New summary text (optional)
            - newType: New entity type (optional)

            **Use Cases:**
            - User refines: "Add to Gromozeka summary that it has voice interface"
            - Reclassify: Change type from "Concept" to "Technology"
            - Enhance: Add missing details to existing summary

            **Returns:** Confirmation message with updated fields
        """.trimIndent()
    
    override val requestType: Class<UpdateMemoryObjectRequest>
        get() = UpdateMemoryObjectRequest::class.java
    
    override fun execute(request: UpdateMemoryObjectRequest, context: ToolContext?): Map<String, Any>
}
