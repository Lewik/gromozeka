# Agent Memory Architecture for Gromozeka

## Overview

Hybrid memory system combining:
- **Vector Store** (semantic similarity) - Mem0-inspired
- **Knowledge Graph** (structured relationships) - Graphiti/Zettelkasten-inspired  
- **Tiered Memory** (context management) - MemGPT-inspired
- **Temporal Awareness** (knowledge evolution) - Graphiti bi-temporal model

## Architecture Phases (Modular Implementation)

### Phase 1: Vector Memory (MVP)

**Goal:** Basic semantic memory

**Components:**
- PostgreSQL + pgvector (already in project)
- Embedding model (choose: OpenAI API or local sentence-transformers)
- Automatic embedding of all messages
- Semantic recall on queries

**Implementation:**

```kotlin
@Service
class VectorMemoryService(
    private val vectorStore: PgVectorStore,
    private val embeddingModel: EmbeddingModel
) {
    suspend fun remember(message: String, threadId: UUID) {
        val embedding = embeddingModel.embed(message)
        vectorStore.add(Document(message, mapOf("threadId" to threadId)))
    }
    
    suspend fun recall(query: String, limit: Int = 5): List<Memory> {
        return vectorStore.similaritySearch(query, limit)
            .map { Memory(it.content, it.metadata) }
    }
}
```

**Database Schema:**

```sql
CREATE TABLE memories (
    id UUID PRIMARY KEY,
    content TEXT NOT NULL,
    embedding vector(1536),
    type VARCHAR(50),
    created_at TIMESTAMP,
    thread_id UUID,
    metadata JSONB
);

CREATE INDEX ON memories USING ivfflat (embedding vector_cosine_ops);
```

**Success Criteria:**
- Agent recalls relevant facts from past conversations
- Automatic embedding of new messages works
- Semantic search returns relevant results

**Can stop here:** Basic memory functionality achieved

---

### Phase 2: Knowledge Graph

**Goal:** Explicit relationships between knowledge

**Components:**
- Neo4j Community Edition (Docker)
- Note + Link data models
- MCP tools for management (create_note, create_link, get_linked_notes)
- Optional: UI visualization

**Data Models:**

```kotlin
data class Note(
    val id: UUID,
    val content: String,
    val type: NoteType,
    val tags: Set<String>,
    val created: Instant,
    val modified: Instant,
    val threadId: UUID?
)

data class Link(
    val fromId: UUID,
    val toId: UUID,
    val relation: RelationType,
    val strength: Double = 1.0,
    val created: Instant
)

enum class NoteType {
    FACT,           // "User prefers Kotlin"
    PREFERENCE,     // "Likes concise answers"
    DECISION,       // "Decided to use Spring AI"
    INSIGHT,        // "Discovered performance issue"
    QUESTION,       // "How to implement X?"
    CONCEPT         // Term definition
}

enum class RelationType {
    EXTENDS,        // Develops idea
    CONTRADICTS,    // Conflicts with
    SUPPORTS,       // Confirms
    EXAMPLE_OF,     // Example of concept
    PART_OF,        // Part of something
    RELATES_TO,     // General relation
    ANSWERS,        // Answers question
    CAUSED_BY,      // Causal
    TEMPORAL        // Time sequence
}
```

**Database Schema:**

```sql
-- Knowledge graph nodes
CREATE TABLE notes (
    id UUID PRIMARY KEY,
    content TEXT NOT NULL,
    type VARCHAR(50),
    tags TEXT[],
    created_at TIMESTAMP,
    modified_at TIMESTAMP,
    thread_id UUID
);

-- Knowledge graph edges
CREATE TABLE links (
    from_note_id UUID REFERENCES notes(id),
    to_note_id UUID REFERENCES notes(id),
    relation VARCHAR(50),
    strength DECIMAL(3,2),
    created_at TIMESTAMP,
    PRIMARY KEY (from_note_id, to_note_id, relation)
);

CREATE INDEX ON links(from_note_id);
CREATE INDEX ON links(to_note_id);
CREATE INDEX ON notes USING GIN(tags);
```

**Service Interface:**

```kotlin
interface KnowledgeGraphService {
    suspend fun createNote(
        content: String,
        type: NoteType,
        tags: Set<String>
    ): UUID
    
    suspend fun link(
        from: UUID,
        to: UUID,
        relation: RelationType,
        strength: Double = 1.0
    )
    
    suspend fun getContext(
        noteId: UUID,
        radius: Int = 2
    ): Graph<Note>
    
    suspend fun findRelevant(
        query: String,
        limit: Int = 10
    ): List<Note>
    
    suspend fun discoverClusters(): List<Cluster>
    suspend fun findHubs(): List<Note>
}
```

**Success Criteria:**
- Can create notes via MCP tools
- Can link notes with typed relationships
- Graph navigation works
- Can retrieve context around a note

**Can stop here:** Structured knowledge management achieved

---

### Phase 3: Hybrid Search

**Goal:** Combine vector + graph for better recall

**Components:**
- Uses Phase 1 VectorMemoryService
- Uses Phase 2 KnowledgeGraphService
- Result merger
- Result ranker

**Implementation:**

```kotlin
@Service
class HybridSearchService(
    private val vectorMemory: VectorMemoryService,
    private val graphMemory: KnowledgeGraphService
) {
    suspend fun recall(query: String, limit: Int = 5): List<Memory> {
        // Parallel search
        val vectorResults = async { vectorMemory.recall(query, limit = 10) }
        val graphResults = async { graphMemory.findRelevant(query, limit = 10) }
        
        // Merge and rank
        return mergeAndRank(
            vectorResults.await(),
            graphResults.await()
        ).take(limit)
    }
    
    private fun mergeAndRank(
        vectorResults: List<Memory>,
        graphResults: List<Note>
    ): List<Memory> {
        return (vectorResults + graphResults.toMemories())
            .distinctBy { it.id }
            .sortedByDescending { result ->
                // Ranking score
                result.relevance * 0.5 +
                result.recency * 0.3 +
                result.importance * 0.2
            }
    }
}
```

**Success Criteria:**
- Recall uses both vector and graph sources
- Results better than either source alone
- Semantic similarity + logical relationships combined

**Can stop here:** Optimal recall quality achieved

---

### Phase 4: Tiered Memory

**Goal:** Manage what to show to the model (MemGPT-inspired)

**Components:**
- Core memory (always in context)
- Working memory (current session)
- Archival memory (uses Phase 3 Hybrid Search)

**Implementation:**

```kotlin
@Service
class TieredMemoryService(
    private val hybridSearch: HybridSearchService,
    private val threadRepository: ThreadRepository
) {
    private val coreMemory = mutableListOf<Memory>()
    private val workingMemory = mutableMapOf<UUID, MutableList<Memory>>()
    
    suspend fun buildContext(
        query: String,
        threadId: UUID
    ): MemoryContext {
        return MemoryContext(
            core = coreMemory,                              // Tier 1: Always
            working = workingMemory[threadId] ?: emptyList(), // Tier 2: Session
            recalled = hybridSearch.recall(query, limit = 5) // Tier 3: Dynamic
        )
    }
    
    suspend fun promoteToCore(memoryId: UUID) {
        val memory = findMemory(memoryId)
        if (memory !in coreMemory) {
            coreMemory.add(memory)
        }
    }
    
    suspend fun evictFromWorking(threadId: UUID, memoryId: UUID) {
        workingMemory[threadId]?.removeIf { it.id == memoryId }
    }
}

data class MemoryContext(
    val core: List<Memory>,      // Always in context
    val working: List<Memory>,   // Current session
    val recalled: List<Memory>   // Dynamically retrieved
)
```

**Success Criteria:**
- Can manage core facts (always in context)
- Working memory per session
- Dynamic recall from archival
- Context fits within model limits

**Can stop here:** Full context management achieved

---

### Phase 5: Advanced Features (Optional)

**5.1 Temporal Awareness:**

```kotlin
// Graphiti-style temporal queries
suspend fun recallAsOf(
    query: String,
    timestamp: Instant
): List<Memory>

// Bi-temporal model
data class TemporalMemory(
    val memory: Memory,
    val validFrom: Instant,      // When fact became true
    val validTo: Instant?,       // When fact stopped being true
    val recordedAt: Instant      // When we learned about it
)
```

**5.2 Pattern Detection:**

```kotlin
suspend fun discoverPatterns(): List<Pattern>
suspend fun findClusters(): List<Cluster>
suspend fun detectAnomalies(): List<Anomaly>
```

**5.3 Self-Management:**

```kotlin
// Agent decides what to remember
suspend fun autoExtract(conversation: Conversation): List<Memory>

// Agent organizes knowledge
suspend fun autoOrganize()
suspend fun consolidateDuplicates()
```

---

## Dependency Graph

```
Phase 1: Vector Memory (standalone)
    ↓
Phase 2: Knowledge Graph (standalone, parallel to Phase 1)
    ↓
Phase 3: Hybrid Search (requires Phase 1 + Phase 2)
    ↓
Phase 4: Tiered Memory (requires Phase 3)
    ↓
Phase 5: Advanced Features (requires Phase 4)
```

**Key:** Phase 1 and Phase 2 are **independent** - can implement in any order!

---

## Integration with SessionSpringAI

```kotlin
class SessionSpringAI(
    private val memorySystem: HybridMemorySystem, // or specific phase service
    private val chatClient: ChatClient,
    // ... other deps
) {
    override suspend fun sendMessage(content: String) {
        // 1. Recall relevant context
        val memoryContext = memorySystem.buildContext(
            query = content,
            threadId = threadId
        )
        
        // 2. Build augmented system prompt
        val systemPrompt = buildSystemPrompt(memoryContext)
        
        // 3. Send to model
        val response = chatClient.prompt()
            .system(systemPrompt)
            .messages(messageHistory)
            .user(content)
            .call()
        
        // 4. Remember (async, don't block response)
        launch {
            memorySystem.remember(
                conversation = listOf(userMessage, assistantMessage),
                threadId = threadId
            )
        }
    }
    
    private fun buildSystemPrompt(context: MemoryContext): String {
        return """
            ${baseSystemPrompt}
            
            ## Relevant Context from Memory:
            
            ### Core Facts (always true):
            ${context.core.joinToString("\n") { "- ${it.content}" }}
            
            ### Current Session:
            ${context.working.joinToString("\n") { "- ${it.content}" }}
            
            ### Recalled from Past:
            ${context.recalled.joinToString("\n") { "- ${it.content}" }}
        """.trimIndent()
    }
}
```

---

## Technology Stack Options

### Cloud-Based (Higher Quality)

```kotlin
// Embeddings
val embeddings = OpenAIEmbeddingModel("text-embedding-3-large")

// LLM for extraction
val llm = OpenAIChatModel("gpt-4o-mini")  // For memory ops
```

### Local (Privacy + Offline)

```kotlin
// Embeddings (sentence-transformers)
val embeddings = SentenceTransformersEmbedding("all-mpnet-base-v2")

// LLM for extraction (Ollama)
val llm = OllamaChatModel(
    api = OllamaApi("http://localhost:11434"),
    options = OllamaOptions.create().withModel("llama3.1:8b")
)
```

### Hybrid (Recommended)

```kotlin
// Claude for main reasoning (already in Gromozeka)
val mainLLM = ClaudeCodeChatModel(...)

// Local LLM for memory operations (cheaper, private)
val memoryLLM = OllamaChatModel(...)

// Embeddings can be local or cloud
val embeddings = OpenAIEmbeddingModel(...)  // or local
```

---

## Deployment Options

### Full Local Stack (docker-compose.yml)

```yaml
version: '3.8'

services:
  postgres:
    image: pgvector/pgvector:pg16
    ports:
      - "5432:5432"
    environment:
      POSTGRES_PASSWORD: password
    volumes:
      - postgres_data:/var/lib/postgresql/data

  neo4j:
    image: neo4j:latest
    ports:
      - "7474:7474"  # UI
      - "7687:7687"  # Bolt
    environment:
      NEO4J_AUTH: neo4j/password
    volumes:
      - neo4j_data:/data

  qdrant:
    image: qdrant/qdrant:latest
    ports:
      - "6333:6333"
    volumes:
      - qdrant_data:/qdrant/storage

volumes:
  postgres_data:
  neo4j_data:
  qdrant_data:
```

**Additional local components:**
- Ollama (install via brew/binary)
- sentence-transformers (pip or JVM binding)

---

## Real-World Examples & Sources

### Mem0
- **Scale:** 186M API calls/month (Q3 2025)
- **Integrations:** AWS Agent SDK, CrewAI, Flowise, Langflow
- **Use Cases:** Customer support bots, therapy bots, finance companions
- **Performance:** -91% latency, -90% tokens vs full-context
- **GitHub:** https://github.com/mem0ai/mem0
- **License:** Apache 2.0

### Graphiti
- **Performance:** <100ms search latency
- **Features:** Bi-temporal model, real-time updates
- **Integrations:** Google ADK, MCP server
- **GitHub:** https://github.com/getzep/graphiti
- **License:** Apache 2.0

### MemGPT
- **Concept:** LLM as Operating System
- **Architecture:** Tiered memory (core + archival)
- **Paper:** https://arxiv.org/pdf/2310.08560
- **Now:** Letta (https://www.letta.com)

### Hybrid Approaches
- **Research:** Combined Text & Graph RAG outperforms single-method
- **Production:** Zep, Mem0g both use hybrid datastores
- **Performance:** Better hit rate on entity-centric, temporal queries

---

## Next Steps

1. **Start with Phase 1 MVP** (Vector Memory)
   - PostgreSQL + pgvector already exists
   - Choose embedding model
   - Implement VectorMemoryService
   - Integrate with SessionSpringAI
   - Test semantic recall

2. **Then Phase 2** (Knowledge Graph)
   - Deploy Neo4j
   - Create Note/Link models
   - Implement KnowledgeGraphService
   - Add MCP tools

3. **Then Phase 3** (Hybrid Search)
   - Combine vector + graph
   - Implement ranking
   - Test recall quality

4. **Optional: Phase 4+**
   - Tiered memory management
   - Advanced features as needed

Each phase is independent and brings value on its own!

---

## References

- **Mem0 Research:** https://mem0.ai/research
- **Graphiti Blog:** https://blog.getzep.com/graphiti-knowledge-graphs-for-agents/
- **MemGPT Paper:** https://arxiv.org/abs/2310.08560
- **OpenAI Cookbook:** Temporal Agents with Knowledge Graphs
- **Zettelkasten Method:** https://zettelkasten.de/introduction/
- **Spring AI Docs:** https://docs.spring.io/spring-ai/reference/

---

*Created for systematic implementation of hybrid agent memory in Gromozeka*
