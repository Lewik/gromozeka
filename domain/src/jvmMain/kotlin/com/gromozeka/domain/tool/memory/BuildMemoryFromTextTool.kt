package com.gromozeka.domain.tool.memory

import com.gromozeka.domain.tool.Tool
import org.springframework.ai.chat.model.ToolContext

/**
 * Request parameters for build_memory_from_text tool.
 * 
 * @property content The text content to extract entities and relationships from (required)
 * @property previousMessages Optional context from previous conversation for better extraction
 */
data class BuildMemoryFromTextRequest(
    val content: String,
    val previousMessages: String? = null
)

/**
 * Domain specification for LLM-based entity extraction from text.
 * 
 * # MCP Tool Exposure
 * 
 * **Tool name:** `build_memory_from_text`
 * 
 * **Exposed via:** Spring AI + MCP protocol
 * 
 * # Purpose
 * 
 * Extract entities and relationships from unstructured text using LLM analysis.
 * Builds knowledge graph automatically from natural language content.
 * 
 * ## Core Features
 * 
 * **Automatic entity extraction:**
 * - Identifies people, technologies, concepts, organizations
 * - Determines entity types automatically
 * - Creates entity summaries from context
 * - Handles ambiguous references
 * 
 * **Relationship discovery:**
 * - Extracts subject-predicate-object triples
 * - Infers relationship types from text
 * - Captures temporal information (when relationship started)
 * - Resolves entity coreferences
 * 
 * **LLM-powered intelligence:**
 * - Understands context and semantics
 * - Handles synonyms and variations
 * - Extracts implicit relationships
 * - Disambiguates entities
 * 
 * # ⚠️ IMPORTANT - Use Sparingly
 * 
 * **This tool is EXPENSIVE:**
 * - Makes MULTIPLE LLM requests (one per entity + relationships)
 * - High token cost (input text analyzed multiple times)
 * - Slow execution (several seconds)
 * - Risk of hallucination (LLM may infer incorrect facts)
 * 
 * **ALWAYS ask user permission before using this tool!**
 * 
 * # When to Use
 * 
 * **Use build_memory_from_text when:**
 * - ✅ Large documents with many interconnected concepts
 * - ✅ Complex technical explanations requiring entity extraction
 * - ✅ Meeting notes, articles, documentation to be indexed
 * - ✅ User explicitly requests: "Remember this document"
 * - ✅ Building initial knowledge base from existing content
 * 
 * **Don't use when:**
 * - ❌ Simple facts like "X uses Y" → use `add_memory_link` instead
 * - ❌ Single relationships → use `add_memory_link` instead
 * - ❌ Structured data (CSV, JSON) → parse and use `add_memory_link`
 * - ❌ User hasn't given permission → ask first!
 * - ❌ Content is already in knowledge graph → query instead
 * 
 * # Parameters
 * 
 * ## content: String (required)
 * 
 * The text content to extract entities and relationships from.
 * 
 * **Best content types:**
 * - Technical documentation (architecture, API docs)
 * - Meeting notes and decisions
 * - Project descriptions
 * - Design documents
 * - Research papers (abstracts/conclusions)
 * 
 * **Content guidelines:**
 * - Prefer focused content (1-5 paragraphs)
 * - Include context (who, what, when, why)
 * - Avoid very long documents (>5000 words) - chunk them
 * - Plain text works best (Markdown okay)
 * 
 * **Examples:**
 * ```
 * "Gromozeka is a multi-agent AI assistant written in Kotlin. 
 * It uses Spring AI for LLM integration, Neo4j for knowledge graph storage,
 * and Qdrant for vector search. The architecture follows Clean Architecture
 * principles with domain, application, and infrastructure layers."
 * ```
 * 
 * ## previousMessages: String? (optional)
 * 
 * Optional context from previous conversation to improve extraction accuracy.
 * 
 * **When to provide:**
 * - Conversation contains entity definitions
 * - Previous messages disambiguate references
 * - Context explains relationships
 * 
 * **When to skip:**
 * - Content is self-contained
 * - No relevant prior context
 * - First message in conversation
 * 
 * # Returns
 * 
 * Returns `Map<String, Any>` with extraction summary:
 * 
 * ## Success Response
 * 
 * ```json
 * {
 *   "type": "text",
 *   "text": "✅ Extracted and saved to knowledge graph:\n\nEntities created (5):\n- Gromozeka (Technology): Multi-agent AI assistant\n- Kotlin (Programming Language): JVM language\n- Spring AI (Framework): LLM integration framework\n- Neo4j (Database): Graph database\n- Qdrant (Database): Vector search engine\n\nRelationships created (4):\n- Gromozeka -[written in]-> Kotlin\n- Gromozeka -[uses]-> Spring AI\n- Gromozeka -[uses]-> Neo4j\n- Gromozeka -[uses]-> Qdrant\n\nProcessing time: 3.2s\nTokens used: ~1500"
 * }
 * ```
 * 
 * **Response contains:**
 * - Count of entities created
 * - List of entities with types and summaries
 * - Count of relationships created
 * - List of relationships (from -[relation]-> to)
 * - Processing time and token usage
 * 
 * ## Error Response
 * 
 * ```json
 * {
 *   "type": "text",
 *   "text": "Error adding to knowledge graph: LLM timeout after 30s"
 * }
 * ```
 * 
 * # Error Cases
 * 
 * | Error | Reason | Solution |
 * |-------|--------|----------|
 * | Empty content | `content` is blank | Provide non-empty text |
 * | LLM timeout | Content too long | Chunk content into smaller pieces |
 * | LLM API error | Rate limit, quota exceeded | Wait and retry, check API key |
 * | Neo4j error | Database unavailable | Check Neo4j service |
 * | Invalid JSON from LLM | LLM response malformed | Retry, report bug if persists |
 * 
 * # Usage Examples
 * 
 * ## Example 1: Extract from Project Description
 * 
 * **Use case:** User provides project overview
 * 
 * ```json
 * {
 *   "tool": "build_memory_from_text",
 *   "parameters": {
 *     "content": "Gromozeka is a multi-agent AI assistant built with Kotlin and Spring AI. It features a hybrid memory architecture combining Neo4j for knowledge graphs and Qdrant for vector search. The system uses Clean Architecture with separate domain, application, and infrastructure layers. Development follows DDD principles with domain-driven specifications."
 *   }
 * }
 * ```
 * 
 * **Result:** Extracts 6+ entities (Gromozeka, Kotlin, Spring AI, Neo4j, Qdrant, Clean Architecture) and 8+ relationships
 * 
 * ## Example 2: Index Meeting Notes
 * 
 * **Use case:** Remember architectural decisions from meeting
 * 
 * ```json
 * {
 *   "tool": "build_memory_from_text",
 *   "parameters": {
 *     "content": "In today's architecture meeting, we decided to use Spring AI instead of LangChain4j because Spring AI has better Kotlin support and integrates well with our existing Spring ecosystem. Alice will implement the ChatModel integration, and Bob will handle the MCP tool registration. Target completion: end of week.",
 *     "previousMessages": "We're building Gromozeka, a multi-agent AI assistant."
 *   }
 * }
 * ```
 * 
 * **Result:** Extracts decisions (Spring AI chosen), people (Alice, Bob), tasks, and timeline
 * 
 * ## Example 3: Index Technical Documentation
 * 
 * **Use case:** Build knowledge from README or docs
 * 
 * ```json
 * {
 *   "tool": "build_memory_from_text",
 *   "parameters": {
 *     "content": "The Tool architecture follows a specification-first pattern. Domain layer contains full specifications as interfaces with complete KDoc. Infrastructure layer contains minimal implementations that delegate to domain services. This enables compiler-enforced contracts and parallel development."
 *   }
 * }
 * ```
 * 
 * **Result:** Extracts architectural patterns and their relationships
 * 
 * # Common Patterns
 * 
 * ## Pattern: Incremental Knowledge Building
 * 
 * Process large documents in chunks:
 * 
 * 1. `build_memory_from_text(intro)` - Extract overview entities
 * 2. `build_memory_from_text(chapter1, previousMessages=intro)` - Add details with context
 * 3. `build_memory_from_text(chapter2, previousMessages=intro)` - Continue building
 * 
 * Result: Complete knowledge graph with context preservation
 * 
 * ## Pattern: Verify Then Correct
 * 
 * Check extraction results and fix mistakes:
 * 
 * 1. `build_memory_from_text(content)` - Initial extraction
 * 2. `get_memory_object("Entity")` - Check what was created
 * 3. `update_memory_object("Entity", newSummary=...)` - Fix errors
 * 4. `invalidate_memory_link("Wrong", "relation", "Target")` - Remove incorrect facts
 * 
 * Result: Accurate knowledge graph after LLM-assisted extraction
 * 
 * ## Pattern: Hybrid Approach
 * 
 * Combine with manual fact addition:
 * 
 * 1. `build_memory_from_text(complexDoc)` - Extract bulk of information
 * 2. `add_memory_link("Detail", "missing from", "Extraction")` - Add specific facts
 * 
 * Result: Comprehensive coverage (automated + manual)
 * 
 * # Transactionality
 * 
 * **Multiple transactions:**
 * - Each entity created in separate transaction
 * - Each relationship created in separate transaction
 * - Partial success possible (some entities created, then error)
 * 
 * **Not atomic:**
 * - If extraction fails midway, some data already persisted
 * - No automatic rollback of created entities
 * - Consider cleanup if extraction fails
 * 
 * **Idempotency:**
 * - NOT idempotent (creates duplicates if called twice)
 * - Same content extracts slightly different entities each time (LLM variance)
 * 
 * # Performance Characteristics
 * 
 * **Execution time:**
 * - Small text (1 paragraph): 2-5 seconds
 * - Medium text (3-5 paragraphs): 5-15 seconds
 * - Large text (1000+ words): 15-60 seconds
 * 
 * **Token usage:**
 * - ~10-50 tokens per entity extracted
 * - ~100-500 tokens per relationship
 * - Total = (entities × 30) + (relationships × 200) approximately
 * 
 * **Cost estimate (GPT-4):**
 * - Small extraction: $0.01-0.05
 * - Medium extraction: $0.05-0.20
 * - Large extraction: $0.20-1.00
 * 
 * **Comparison to add_memory_link:**
 * - build_memory_from_text: 5-60s, $0.01-1.00, probabilistic
 * - add_memory_link (10 facts): <0.1s, $0.00, deterministic
 * 
 * **Recommendation:** Use `add_memory_link` whenever possible. Only use this for truly unstructured content.
 * 
 * # LLM Extraction Strategy
 * 
 * **Entity extraction:**
 * 1. LLM identifies potential entities in text
 * 2. Classifies each entity (Person, Technology, Concept, etc.)
 * 3. Generates summary from context
 * 4. Resolves coreferences (e.g., "it" refers to "Gromozeka")
 * 
 * **Relationship extraction:**
 * 1. LLM identifies connections between entities
 * 2. Determines relationship type (uses, created by, depends on)
 * 3. Extracts temporal information if present
 * 4. Validates relationship makes sense
 * 
 * **Limitations:**
 * - May hallucinate relationships not explicitly stated
 * - May miss subtle or complex relationships
 * - May create duplicate entities with slight name variations
 * - Extraction quality depends on LLM capability and prompt
 * 
 * # Related Tools
 * 
 * - **add_memory_link** - Direct fact addition (fast, deterministic, free) - PREFER THIS
 * - **get_memory_object** - Verify extracted entities
 * - **update_memory_object** - Fix extraction errors
 * - **invalidate_memory_link** - Remove incorrect relationships
 * - **delete_memory_object** - Clean up duplicate entities
 * 
 * # Infrastructure Implementation
 * 
 * See infrastructure-ai module for Spring AI integration:
 * @see com.gromozeka.infrastructure.ai.tool.memory.BuildMemoryFromTextTool
 * 
 * # Related Domain Services
 * 
 * This tool delegates to:
 * @see com.gromozeka.domain.service.KnowledgeGraphService.extractAndSave
 */
interface BuildMemoryFromTextTool : Tool<BuildMemoryFromTextRequest, Map<String, Any>> {
    
    override val name: String
        get() = "build_memory_from_text"
    
    override val description: String
        get() = """
            Remember information from text by building memory objects and links using LLM extraction.

            ⚠️ **IMPORTANT - Use Sparingly:**
            - This tool makes MULTIPLE LLM requests (one per entity + relationships)
            - Expensive in tokens and time
            - Use ONLY for large, complex texts with many entities
            - For simple facts, use add_memory_link instead (direct, no LLM parsing)
            - ALWAYS ask user permission before using this tool

            **When to use:**
            - ✅ Large documents with many interconnected concepts
            - ✅ Complex technical explanations requiring entity extraction
            - ❌ Simple facts like "X uses Y" (use add_memory_link)
            - ❌ Single relationships (use add_memory_link)

            **Usage:**
            - Extract entities (people, technologies, concepts, etc.) from the content
            - Identify relationships between entities with temporal information
            - Store in the knowledge graph with bi-temporal tracking

            **Parameters:**
            - content: The text content to extract from (required)
            - previousMessages: Optional context from previous conversation

            **Returns:** Confirmation message with count of entities and relationships added
        """.trimIndent()
    
    override val requestType: Class<BuildMemoryFromTextRequest>
        get() = BuildMemoryFromTextRequest::class.java
    
    override fun execute(request: BuildMemoryFromTextRequest, context: ToolContext?): Map<String, Any>
}
