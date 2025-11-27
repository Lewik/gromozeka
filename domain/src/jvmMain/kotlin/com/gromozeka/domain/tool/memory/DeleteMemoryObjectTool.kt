package com.gromozeka.domain.tool.memory

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for delete_memory_object tool.
 * 
 * @property name Entity name to permanently delete (required)
 * @property cascade Delete all relationships too (default: true)
 */
data class DeleteMemoryObjectRequest(
    val name: String,
    val cascade: Boolean = true
)

/**
 * Domain specification for permanent entity deletion from knowledge graph.
 * 
 * # MCP Tool Exposure
 * 
 * **Tool name:** `delete_memory_object`
 * 
 * **Exposed via:** Spring AI + MCP protocol
 * 
 * # ⚠️ DANGER - DESTRUCTIVE OPERATION
 * 
 * **THIS IS A PERMANENT DELETE - NO UNDO!**
 * 
 * This tool **permanently removes** an entity from the Neo4j knowledge graph database.
 * There is **NO WAY** to recover deleted data. History is **NOT** preserved.
 * 
 * **PREFER `invalidate_memory_link` FOR ALMOST ALL USE CASES!**
 * 
 * # Purpose
 * 
 * Permanently delete an entity and optionally its relationships from the knowledge graph.
 * Should only be used for data cleanup, test data removal, or data purge requirements.
 * 
 * ## Core Features
 * 
 * **Hard delete:**
 * - Entity permanently removed from Neo4j
 * - No history preservation (unlike invalidate)
 * - No recovery possible
 * - Immediate effect
 * 
 * **Cascade option:**
 * - `cascade=true` (default): Deletes all relationships
 * - `cascade=false`: Only deletes entity if no relationships exist
 * - Prevents orphaned relationships
 * 
 * **Immediate effect:**
 * - Entity vanishes from all queries instantly
 * - Historical queries cannot find it
 * - Related entities remain (only relationships deleted)
 * 
 * # When to Use
 * 
 * **Use delete_memory_object when:**
 * - Removing test/dummy data from development
 * - Cleaning up incorrectly created entities
 * - Removing sensitive data that must be purged (GDPR, compliance)
 * - Deleting duplicate entities from extraction errors
 * - Initial knowledge graph setup/cleanup
 * 
 * **When NOT to Use (Use invalidate_memory_link instead):**
 * - ❌ Correcting mistakes → use `invalidate_memory_link`
 * - ❌ Updating information → use `update_memory_object`
 * - ❌ Temporary removal → use `invalidate_memory_link`
 * - ❌ Tracking changes over time → use `invalidate_memory_link`
 * - ❌ Unsure if should delete → DON'T delete, use `invalidate_memory_link`
 * 
 * # Parameters
 * 
 * ## name: String (required)
 * 
 * Entity name to permanently delete (exact match, case-sensitive).
 * 
 * **Examples:**
 * - `"TestEntity"` - Test data
 * - `"DuplicateGromozeka"` - Duplicate from extraction error
 * - `"SensitiveData"` - Data that must be purged
 * 
 * **Validation:**
 * - Must exist in knowledge graph
 * - Exact name match (case-sensitive)
 * - Cannot be empty
 * - **NO CONFIRMATION PROMPT** - immediate deletion!
 * 
 * ## cascade: Boolean (optional, default: true)
 * 
 * Whether to delete all relationships connected to this entity.
 * 
 * **cascade=true (default):**
 * - Deletes entity AND all its relationships
 * - Both incoming and outgoing relationships deleted
 * - Safe (no orphaned relationships)
 * - **RECOMMENDED** for most use cases
 * 
 * **cascade=false:**
 * - Only deletes entity if it has NO relationships
 * - Fails with error if relationships exist
 * - Prevents accidental data loss
 * - Use when you want safety check
 * 
 * # Returns
 * 
 * Returns `Map<String, Any>` with deletion confirmation:
 * 
 * ## Success Response (cascade=true)
 * 
 * ```json
 * {
 *   "type": "text",
 *   "text": "⚠️ PERMANENTLY DELETED entity: TestEntity\n\nDeleted:\n- 1 entity node\n- 5 relationships (3 outgoing, 2 incoming)\n\nAffected entities (relationships removed):\n- Kotlin (TestEntity -[written in]-> Kotlin)\n- Spring AI (TestEntity -[uses]-> Spring AI)\n- Alice (Alice -[created]-> TestEntity)\n\n❌ THIS ACTION CANNOT BE UNDONE!\n\nTimestamp: 2025-11-27T17:00:00Z"
 * }
 * ```
 * 
 * **Response contains:**
 * - Warning about permanent deletion
 * - Count of deleted nodes
 * - Count of deleted relationships
 * - List of affected entities
 * - Timestamp of deletion
 * - Reminder that action is irreversible
 * 
 * ## Success Response (cascade=false, no relationships)
 * 
 * ```json
 * {
 *   "type": "text",
 *   "text": "⚠️ PERMANENTLY DELETED entity: OrphanedEntity\n\nDeleted:\n- 1 entity node\n- 0 relationships (entity had no connections)\n\n❌ THIS ACTION CANNOT BE UNDONE!\n\nTimestamp: 2025-11-27T17:00:00Z"
 * }
 * ```
 * 
 * ## Error Response (cascade=false, has relationships)
 * 
 * ```json
 * {
 *   "type": "text",
 *   "text": "Error hard deleting entity: Cannot delete 'EntityName' because it has 5 relationships. Use cascade=true to delete with relationships, or invalidate_memory_link to preserve history."
 * }
 * ```
 * 
 * ## Error Response (entity not found)
 * 
 * ```json
 * {
 *   "type": "text",
 *   "text": "Error hard deleting entity: Entity 'UnknownEntity' not found in knowledge graph"
 * }
 * ```
 * 
 * # Error Cases
 * 
 * | Error | Reason | Solution |
 * |-------|--------|----------|
 * | Empty name | `name` is blank | Provide entity name |
 * | Entity not found | Entity doesn't exist | Already deleted or wrong name |
 * | Has relationships (cascade=false) | Entity has connections | Use cascade=true or remove relationships first |
 * | Neo4j error | Database unavailable | Check Neo4j service |
 * 
 * # Usage Examples
 * 
 * ## Example 1: Delete Test Entity
 * 
 * **Use case:** Remove test data after development
 * 
 * ```json
 * {
 *   "tool": "delete_memory_object",
 *   "parameters": {
 *     "name": "TestEntity",
 *     "cascade": true
 *   }
 * }
 * ```
 * 
 * **Result:** TestEntity and all its relationships permanently deleted
 * 
 * ## Example 2: Safe Delete (No Cascade)
 * 
 * **Use case:** Delete only if entity is orphaned
 * 
 * ```json
 * {
 *   "tool": "delete_memory_object",
 *   "parameters": {
 *     "name": "OrphanedEntity",
 *     "cascade": false
 *   }
 * }
 * ```
 * 
 * **Result:** Deletes if no relationships, errors otherwise
 * 
 * ## Example 3: Clean Up Duplicate
 * 
 * **Use case:** LLM created duplicate entity with slightly different name
 * 
 * ```json
 * {
 *   "tool": "delete_memory_object",
 *   "parameters": {
 *     "name": "Gromozeka (duplicate)",
 *     "cascade": true
 *   }
 * }
 * ```
 * 
 * **Result:** Duplicate removed, canonical entity remains
 * 
 * ## Example 4: Data Purge (GDPR)
 * 
 * **Use case:** Remove sensitive personal data
 * 
 * ```json
 * {
 *   "tool": "delete_memory_object",
 *   "parameters": {
 *     "name": "User_PersonalData",
 *     "cascade": true
 *   }
 * }
 * ```
 * 
 * **Result:** All traces of entity removed from knowledge graph
 * 
 * # Common Patterns
 * 
 * ## Pattern: Verify Before Delete
 * 
 * Always check what will be deleted:
 * 
 * 1. `get_memory_object("EntityToDelete")` - See what exists
 * 2. Review relationships (what will be affected?)
 * 3. **Consider `invalidate_memory_link` instead!**
 * 4. If truly must delete: `delete_memory_object("EntityToDelete", cascade=true)`
 * 
 * Result: Informed deletion decision
 * 
 * ## Pattern: Batch Test Data Cleanup
 * 
 * Remove multiple test entities:
 * 
 * ```kotlin
 * val testEntities = listOf("Test1", "Test2", "Test3")
 * for (entity in testEntities) {
 *     delete_memory_object(entity, cascade=true)
 * }
 * ```
 * 
 * Result: Clean knowledge graph after testing
 * 
 * ## Pattern: Replace With Invalidation
 * 
 * **BETTER APPROACH - Use this instead of delete:**
 * 
 * Instead of:
 * ```json
 * {"tool": "delete_memory_object", "parameters": {"name": "OldTech"}}
 * ```
 * 
 * Do this:
 * ```json
 * [
 *   {"tool": "invalidate_memory_link", "parameters": {"from": "Project", "relation": "uses", "to": "OldTech"}},
 *   {"tool": "add_memory_link", "parameters": {"from": "Project", "relation": "uses", "to": "NewTech"}}
 * ]
 * ```
 * 
 * Result: History preserved, current state correct, no data loss
 * 
 * # Transactionality
 * 
 * **Single transaction:**
 * - Entity and relationships deleted atomically
 * - Either all deleted or none deleted
 * - No partial state
 * 
 * **NOT idempotent:**
 * - First call: deletes entity
 * - Second call: error (entity not found)
 * - Check existence before calling
 * 
 * **Concurrency:**
 * - Safe for concurrent deletes of different entities
 * - Concurrent delete of same entity: first wins, second errors
 * 
 * # Performance Characteristics
 * 
 * - **Execution time:** <50ms for small entities, 100-500ms for highly connected entities
 * - **Scalability:** Depends on relationship count (cascade=true)
 * - **Token cost:** 0 (no LLM calls)
 * 
 * **High-degree nodes:**
 * - Entity with 1000+ relationships takes longer
 * - Consider relationship cleanup first if performance critical
 * 
 * # Cascade Behavior Details
 * 
 * **cascade=true:**
 * 1. Find all relationships (incoming + outgoing)
 * 2. Delete all relationships
 * 3. Delete entity node
 * 4. Return count of deleted items
 * 
 * **cascade=false:**
 * 1. Check if entity has any relationships
 * 2. If yes: error, refuse to delete
 * 3. If no: delete entity node
 * 4. Return confirmation
 * 
 * **Affected entities:**
 * - Other entities in deleted relationships remain
 * - Only relationship edges removed
 * - No cascade to connected entities (only one entity deleted)
 * 
 * # Why Invalidation is Better
 * 
 * **Invalidation advantages:**
 * - ✅ Preserves history (audit trail)
 * - ✅ Reversible (can query historical state)
 * - ✅ Tracks knowledge evolution
 * - ✅ Supports temporal queries
 * - ✅ Safe (no data loss)
 * - ✅ Debuggable (see what changed and when)
 * 
 * **Deletion advantages:**
 * - ✅ Frees disk space
 * - ✅ Removes sensitive data completely
 * - ✅ Cleans up test data
 * 
 * **Default choice:** Use `invalidate_memory_link`. Only delete for:
 * - Test data cleanup
 * - Duplicate entity removal
 * - Data purge requirements (GDPR, compliance)
 * 
 * # Safety Checklist
 * 
 * Before deleting, ask yourself:
 * 
 * - [ ] Is this test/dummy data? (YES = okay to delete)
 * - [ ] Do I need to track this change? (YES = use invalidate instead)
 * - [ ] Will I need this data for historical queries? (YES = use invalidate instead)
 * - [ ] Is this a mistake correction? (YES = use invalidate instead)
 * - [ ] Have I checked `get_memory_object` to see what will be deleted?
 * - [ ] Am I sure there's no alternative? (Consider invalidate first)
 * - [ ] Is this required by data protection law? (GDPR = okay to delete)
 * 
 * **If any doubt: DON'T delete, use `invalidate_memory_link` instead!**
 * 
 * # Related Tools
 * 
 * - **invalidate_memory_link** - Soft delete with history (PREFER THIS!)
 * - **get_memory_object** - Check what will be deleted before deleting
 * - **update_memory_object** - Modify entity instead of deleting
 * - **add_memory_link** - Create entity (opposite of delete)
 * 
 * # Infrastructure Implementation
 * 
 * See infrastructure-ai module for Spring AI integration:
 * @see com.gromozeka.infrastructure.ai.tool.memory.DeleteMemoryObjectTool
 * 
 * # Related Domain Services
 * 
 * This tool delegates to:
 * @see com.gromozeka.domain.service.MemoryManagementService.hardDeleteEntity
 */
interface DeleteMemoryObjectTool : Tool<DeleteMemoryObjectRequest, Map<String, Any>> {
    
    override val name: String
        get() = "delete_memory_object"
    
    override val description: String
        get() = """
            ⚠️ DANGER: Permanently delete an entity from the knowledge graph (NO UNDO!)

            **THIS IS A DESTRUCTIVE OPERATION:**
            - Entity is permanently removed from Neo4j database
            - NO history preservation (unlike invalidate_memory_link)
            - NO way to recover deleted data
            - If cascade=true, ALL relationships are also deleted

            **USE WITH EXTREME CAUTION!**

            **Parameters:**
            - name: Entity name to permanently delete (required)
            - cascade: Delete all relationships too (default: true)

            **When to Use:**
            - Removing test/dummy data
            - Cleaning up incorrectly created entities
            - Removing sensitive data that must be purged

            **When NOT to Use:**
            - Correcting mistakes → use invalidate_memory_link instead
            - Updating information → use update_memory_object instead
            - Temporary removal → use invalidate_memory_link instead

            **Safe Alternative:**
            Use invalidate_memory_link() for soft delete with history preservation!

            **Returns:** Confirmation message with count of deleted nodes and relationships
        """.trimIndent()
    
    override val requestType: Class<DeleteMemoryObjectRequest>
        get() = DeleteMemoryObjectRequest::class.java
    
    override fun execute(request: DeleteMemoryObjectRequest, context: ToolContext?): Map<String, Any>
}
