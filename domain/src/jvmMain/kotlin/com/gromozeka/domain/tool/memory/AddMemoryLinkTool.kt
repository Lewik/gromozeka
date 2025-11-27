package com.gromozeka.domain.tool.memory

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for add_memory_link tool.
 * 
 * @property from Source entity name (e.g., "Gromozeka", "Kotlin")
 * @property relation Relationship type (e.g., "written in", "uses", "created by")
 * @property to Target entity name (e.g., "Kotlin", "Spring Framework")
 * @property summary Optional summary for source entity (only set if entity doesn't exist)
 */
data class AddMemoryLinkRequest(
    val from: String,
    val relation: String,
    val to: String,
    val summary: String? = null
)

/**
 * Domain specification for direct fact addition to knowledge graph.
 * 
 * # MCP Tool Exposure
 * 
 * **Tool name:** `add_memory_link`
 * 
 * **Exposed via:** Spring AI + MCP protocol
 * 
 * # Purpose
 * 
 * Directly add a structured fact to the knowledge graph without LLM parsing.
 * Fast, accurate, and deterministic way to record explicit information.
 * 
 * ## Core Features
 * 
 * **Direct entity creation:**
 * - Creates source entity if not exists
 * - Creates target entity if not exists
 * - Creates relationship between them
 * - No LLM parsing overhead
 * 
 * **Bi-temporal tracking:**
 * - Records `created_at` timestamp
 * - Relationship marked as valid from now
 * - Can be invalidated later with `invalidate_memory_link`
 * - Historical queries supported ("What was true on date X?")
 * 
 * **Efficiency:**
 * - Single database transaction
 * - No LLM calls (unlike `build_memory_from_text`)
 * - Instant execution
 * - Guaranteed accuracy (no hallucination risk)
 * 
 * # When to Use
 * 
 * **Use add_memory_link when:**
 * - User provides explicit fact: "Gromozeka is written in Kotlin"
 * - Recording configuration: "Project uses Spring AI"
 * - Documenting dependencies: "Service depends on PostgreSQL"
 * - Tracking relationships: "Alice created UserService"
 * - Simple, structured information (subject-predicate-object)
 * 
 * **Don't use when:**
 * - Need to extract multiple facts from text → use `build_memory_from_text`
 * - Information is unstructured prose → use `build_memory_from_text`
 * - Updating existing fact → use `invalidate_memory_link` then `add_memory_link`
 * - Querying information → use `get_memory_object`
 * 
 * # Parameters
 * 
 * ## from: String (required)
 * 
 * Source entity name (subject of the relationship).
 * 
 * **Examples:**
 * - `"Gromozeka"` - Project name
 * - `"ThreadRepository"` - Class name
 * - `"Alice"` - Person name
 * - `"Spring AI"` - Technology name
 * 
 * **Behavior:**
 * - If entity exists: use existing entity
 * - If entity doesn't exist: create new entity with `summary` (if provided)
 * - Entity name is case-sensitive
 * 
 * **Best practices:**
 * - Use consistent naming (e.g., always "Spring AI", not "SpringAI" or "Spring-AI")
 * - Use canonical names (e.g., "Kotlin" not "Kotlin language")
 * - For code entities, use fully qualified names if ambiguous
 * 
 * ## relation: String (required)
 * 
 * Relationship type (predicate connecting subject and object).
 * 
 * **Common relation types:**
 * - `"written in"` - Programming language (Gromozeka written in Kotlin)
 * - `"uses"` - Dependency/tool usage (Project uses Spring Framework)
 * - `"created by"` - Authorship (UserService created by Alice)
 * - `"depends on"` - Technical dependency (Service depends on Database)
 * - `"implements"` - Interface implementation (Class implements Interface)
 * - `"extends"` - Inheritance (Class extends BaseClass)
 * - `"located in"` - Physical/logical location (File located in src/)
 * 
 * **Best practices:**
 * - Use present tense for current facts
 * - Use past tense for historical facts
 * - Be consistent with relation names
 * - Use lowercase for relation types
 * - Prefer standard relations over custom ones
 * 
 * ## to: String (required)
 * 
 * Target entity name (object of the relationship).
 * 
 * **Examples:**
 * - `"Kotlin"` - Programming language
 * - `"Spring Framework"` - Technology
 * - `"PostgreSQL"` - Database
 * - `"domain/"` - Directory path
 * 
 * **Behavior:**
 * - Same as `from`: creates if doesn't exist
 * - No summary for target entity (only source can have summary)
 * 
 * ## summary: String? (optional)
 * 
 * Optional summary for source entity (only used if entity doesn't exist).
 * 
 * **When to provide:**
 * - First time mentioning entity
 * - Entity needs context (e.g., "Multi-agent AI assistant with hybrid memory")
 * - Disambiguating common names
 * 
 * **When to skip:**
 * - Entity already exists
 * - Entity is self-explanatory (e.g., "Kotlin", "PostgreSQL")
 * - Will be updated later with `update_memory_object`
 * 
 * # Returns
 * 
 * Returns `Map<String, Any>` with confirmation message:
 * 
 * ## Success Response
 * 
 * ```json
 * {
 *   "type": "text",
 *   "text": "✅ Added fact: Gromozeka -[written in]-> Kotlin\n\nCreated entities:\n- Gromozeka (new)\n- Kotlin (existing)\n\nRelationship created with timestamp: 2025-11-27T10:30:00Z"
 * }
 * ```
 * 
 * **Response contains:**
 * - Confirmation of created fact
 * - Which entities were created vs existing
 * - Timestamp of relationship creation
 * 
 * ## Error Response
 * 
 * ```json
 * {
 *   "type": "text",
 *   "text": "Error adding fact to knowledge graph: Neo4j connection refused"
 * }
 * ```
 * 
 * # Error Cases
 * 
 * | Error | Reason | Solution |
 * |-------|--------|----------|
 * | Empty entity names | `from` or `to` is blank | Provide non-empty names |
 * | Empty relation | `relation` is blank | Provide relationship type |
 * | Neo4j connection error | Database unavailable | Check Neo4j service |
 * | Constraint violation | Duplicate relationship | Use existing relationship or invalidate first |
 * 
 * # Usage Examples
 * 
 * ## Example 1: Record Technology Stack
 * 
 * **Use case:** User says "Gromozeka is written in Kotlin"
 * 
 * ```json
 * {
 *   "tool": "add_memory_link",
 *   "parameters": {
 *     "from": "Gromozeka",
 *     "relation": "written in",
 *     "to": "Kotlin",
 *     "summary": "Multi-agent AI assistant with hybrid memory architecture"
 *   }
 * }
 * ```
 * 
 * **Result:** Creates entities (if new) and relationship
 * 
 * ## Example 2: Document Dependency
 * 
 * **Use case:** Recording that project uses specific framework
 * 
 * ```json
 * {
 *   "tool": "add_memory_link",
 *   "parameters": {
 *     "from": "Gromozeka",
 *     "relation": "uses",
 *     "to": "Spring AI"
 *   }
 * }
 * ```
 * 
 * **Result:** Links existing Gromozeka entity to Spring AI
 * 
 * ## Example 3: Track Authorship
 * 
 * **Use case:** Recording who created a component
 * 
 * ```json
 * {
 *   "tool": "add_memory_link",
 *   "parameters": {
 *     "from": "ThreadRepository",
 *     "relation": "created by",
 *     "to": "Repository Agent",
 *     "summary": "Repository for conversation thread persistence"
 *   }
 * }
 * ```
 * 
 * **Result:** Documents authorship with context
 * 
 * ## Example 4: Record Architectural Decision
 * 
 * **Use case:** Documenting why a choice was made
 * 
 * ```json
 * {
 *   "tool": "add_memory_link",
 *   "parameters": {
 *     "from": "Memory Architecture",
 *     "relation": "uses",
 *     "to": "Neo4j",
 *     "summary": "Bi-temporal knowledge graph with entity extraction"
 *   }
 * }
 * ```
 * 
 * **Result:** Links architectural component to technology choice
 * 
 * # Common Patterns
 * 
 * ## Pattern: Technology Stack Documentation
 * 
 * Multiple calls to build complete picture:
 * 
 * ```kotlin
 * add_memory_link("Gromozeka", "written in", "Kotlin")
 * add_memory_link("Gromozeka", "uses", "Spring Framework")
 * add_memory_link("Gromozeka", "uses", "Spring AI")
 * add_memory_link("Gromozeka", "uses", "Neo4j")
 * add_memory_link("Gromozeka", "uses", "Qdrant")
 * ```
 * 
 * Result: Complete technology stack documented
 * 
 * ## Pattern: Correcting Mistakes
 * 
 * 1. Invalidate wrong fact: `invalidate_memory_link("Gromozeka", "written in", "Java")`
 * 2. Add correct fact: `add_memory_link("Gromozeka", "written in", "Kotlin")`
 * 
 * Result: History preserved, current state correct
 * 
 * ## Pattern: Incremental Knowledge Building
 * 
 * Start minimal, enhance later:
 * 
 * 1. Initial: `add_memory_link("UserService", "uses", "PostgreSQL")`
 * 2. Enhance: `update_memory_object("UserService", newSummary="Handles user authentication and profile management")`
 * 
 * Result: Knowledge grows over time
 * 
 * # Transactionality
 * 
 * **Single transaction:**
 * - Creates both entities and relationship atomically
 * - Either all succeed or all fail (no partial state)
 * - Consistent even under concurrent access
 * 
 * **Idempotency:**
 * - NOT strictly idempotent (creates duplicate relationships if called twice)
 * - Consider adding uniqueness constraints in infrastructure if needed
 * 
 * **Concurrency:**
 * - Safe for concurrent calls with different entity pairs
 * - May create duplicate relationships for same pair (infrastructure should handle)
 * 
 * # Performance Characteristics
 * 
 * - **Execution time:** <10ms (single database write)
 * - **LLM calls:** 0 (no AI involvement)
 * - **Token cost:** 0 tokens
 * - **Scalability:** Excellent (simple graph insert)
 * 
 * **Comparison to build_memory_from_text:**
 * - add_memory_link: instant, deterministic, no tokens
 * - build_memory_from_text: slow (multiple LLM calls), probabilistic, expensive
 * 
 * **Recommendation:** Always prefer `add_memory_link` for simple facts.
 * 
 * # Bi-Temporal Model
 * 
 * **Valid time tracking:**
 * - Relationship has `created_at` timestamp (when it became true)
 * - Relationship has `invalid_at` timestamp (when it stopped being true, null = still valid)
 * 
 * **Historical queries supported:**
 * - "What did Gromozeka use on 2025-01-01?" → queries facts valid on that date
 * - "When did we start using Spring AI?" → queries `created_at` timestamps
 * 
 * **Invalidation preserves history:**
 * - `invalidate_memory_link` sets `invalid_at`, doesn't delete
 * - Old facts remain queryable for historical analysis
 * - Temporal evolution of knowledge tracked
 * 
 * # Related Tools
 * 
 * - **build_memory_from_text** - Extract multiple facts from unstructured text (slow, LLM-based)
 * - **get_memory_object** - Retrieve entity details and relationships
 * - **update_memory_object** - Modify entity summary or type
 * - **invalidate_memory_link** - Mark fact as outdated (soft delete)
 * - **delete_memory_object** - Permanently delete entity (DANGER, hard delete)
 * 
 * # Infrastructure Implementation
 * 
 * See infrastructure-ai module for Spring AI integration:
 * @see com.gromozeka.infrastructure.ai.tool.memory.AddMemoryLinkTool
 * 
 * # Related Domain Services
 * 
 * This tool delegates to:
 * @see com.gromozeka.domain.service.MemoryManagementService.addFactDirectly
 */
interface AddMemoryLinkTool : Tool<AddMemoryLinkRequest, Map<String, Any>> {
    
    override val name: String
        get() = "add_memory_link"
    
    override val description: String
        get() = """
            Directly add a fact to the knowledge graph without LLM parsing.
            Use this when the user provides explicit, structured information.

            **Usage:**
            - User gives ready information: "Gromozeka is written in Kotlin"
            - No LLM extraction needed - creates entities and relationship directly
            - Faster and more accurate than parsing through build_memory_from_text

            **Parameters:**
            - from: Source entity name (e.g., "Gromozeka")
            - relation: Relationship type (e.g., "written in", "uses", "created by")
            - to: Target entity name (e.g., "Kotlin")
            - summary: Optional summary for source entity

            **Returns:** Confirmation message with created entities and relationship
        """.trimIndent()
    
    override val requestType: Class<AddMemoryLinkRequest>
        get() = AddMemoryLinkRequest::class.java
    
    override fun execute(request: AddMemoryLinkRequest, context: ToolContext?): Map<String, Any>
}
