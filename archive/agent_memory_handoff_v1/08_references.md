# 08. References

Ниже — источники, на которые я опирался при проектировании пакета.
Список не обязателен для реализации, но полезен разработчику как map of the territory.

## Product / framework docs

### [R1] LangMem — Conceptual Guide
Long-term Memory in LLM Applications
https://langchain-ai.github.io/langmem/concepts/conceptual_guide/

Что полезно:
- semantic / episodic / procedural split
- общая схема memory operation
- framing long-term memory как отдельного subsystem

### [R2] LangMem — Extract Semantic Memories
How to Extract Semantic Memories
https://langchain-ai.github.io/langmem/guides/extract_semantic_memories/

Что полезно:
- structured extraction
- memory manager pattern
- collections / profile split

### [R3] LangMem — Manage User Profiles
How to Manage User Profiles
https://langchain-ai.github.io/langmem/guides/manage_user_profile/

Что полезно:
- compact user profile как отдельный тип памяти
- profile schema-driven maintenance

### [R4] LangMem — Background Quickstart
Background Quickstart Guide
https://langchain-ai.github.io/langmem/background_quickstart/

Что полезно:
- background memory processing
- separation between hot path and background writes

### [R5] LangMem — Delayed Background Processing
Delayed Background Memory Processing
https://langchain-ai.github.io/langmem/guides/delayed_processing/

Что полезно:
- debounce pattern
- memory update после “затишья”, а не на каждый turn

### [R6] Letta — Memory Blocks
Memory blocks (core memory)
https://docs.letta.com/guides/core-concepts/memory/memory-blocks/

Что полезно:
- always-in-context memory blocks
- idea of small pinned memory surface

### [R7] Letta — Archival Memory
Archival memory
https://docs.letta.com/guides/core-concepts/memory/archival-memory/

Что полезно:
- searchable memory outside prompt
- on-demand retrieval instead of pinning everything

### [R8] Letta — Context Hierarchy
Context hierarchy
https://docs.letta.com/guides/core-concepts/memory/context-hierarchy

Что полезно:
- tiered context design
- distinction between prompt memory, file search, archival memory, external DB

### [R9] LangGraph / LangChain — Long-term Memory
Long-term memory
https://docs.langchain.com/oss/python/langchain/long-term-memory

Что полезно:
- namespace/key-oriented storage idea
- JSON documents as a long-term store abstraction

### [R10] LangGraph — Persistence
Persistence
https://docs.langchain.com/oss/python/langgraph/persistence

Что полезно:
- checkpoints
- durable agent execution and thread state

## Papers / benchmarks

### [R11] Mem0
Mem0: Building Production-Ready AI Agents with Scalable Long-Term Memory
https://arxiv.org/abs/2504.19413

Что полезно:
- extraction + update pipeline
- salience and scalable long-term memory framing
- background summarization/reflection ideas

### [R12] A-MEM
A-MEM: Agentic Memory for LLM Agents
https://arxiv.org/abs/2502.12110

Что полезно:
- richer note structure
- contextual descriptions, keywords, tags
- dynamic linking and memory evolution

### [R13] LongMemEval
LongMemEval: Benchmarking Chat Assistants on Long-Term Interactive Memory
https://arxiv.org/abs/2410.10813

Что полезно:
- capability buckets:
  - information extraction
  - multi-session reasoning
  - temporal reasoning
  - knowledge updates
  - abstention
- time-aware retrieval ideas

### [R14] LoCoMo
Evaluating Very Long-Term Conversational Memory of LLM Agents
https://arxiv.org/abs/2402.17753

Что полезно:
- long multi-session dialogue setup
- event-based memory testing
- QA / summarization over long histories

### [R15] MemOS
MemOS: A Memory OS for AI System
https://arxiv.org/abs/2507.03724

Что полезно:
- memory tiers and hierarchy as a system design idea
- memory as a managed resource

### [R16] Does Memory Need Graphs?
Does Memory Need Graphs? A Unified Framework and Analysis
https://arxiv.org/abs/2601.01280

Что полезно:
- graph vs non-graph framing
- useful if later захочется переоценить необходимость graph DB

### [R17] All-Mem
All-Mem: Agentic Lifelong Memory via Dynamic Topology Consolidation
https://arxiv.org/abs/2603.19595

Что полезно:
- online/offline consolidation
- non-destructive topology editing
- immutable evidence mindset

### [R18] MemReader
MemReader: From Passive to Active Extraction for Agent Memory
https://arxiv.org/abs/2604.07877

Что полезно:
- emphasis on memory extraction quality
- useful for future prompt/eval tuning

## Storage / infra docs

### [R19] pgvector
pgvector for Postgres
https://github.com/pgvector/pgvector

Что полезно:
- vectors inside Postgres
- exact + ANN search
- joins with structured data

### [R20] PostgreSQL news on pgvector 0.8.0
pgvector 0.8.0 Released!
https://www.postgresql.org/about/news/pgvector-080-released-2952/

Что полезно:
- filtered query performance improvements
- HNSW improvements

### [R21] Qdrant — Filtering
Filtering
https://qdrant.tech/documentation/search/filtering/

Что полезно:
- metadata-aware vector retrieval
- payload filtering patterns

### [R22] Qdrant — Payload indexing
Payload
https://qdrant.tech/documentation/manage-data/payload/

Что полезно:
- indexing payload fields for filtered search

### [R23] Qdrant — Search
Search
https://qdrant.tech/documentation/search/search/

Что полезно:
- filtered ANN notes
- ACORN search algorithm mention for strict filters

### [R24] Neo4j — Graph DB Concepts
Graph database concepts
https://neo4j.com/docs/getting-started/appendix/graphdb-concepts/

Что полезно:
- traversals and paths
- why graph DB matters only when graph queries are first-class

### [R25] Neo4j — What is a graph database
What is a graph database
https://neo4j.com/docs/getting-started/graph-database/

Что полезно:
- nodes / relationships / properties as a graph-native model

## Как я бы использовал этот список

### Обязательные для чтения перед реализацией
- R1
- R2
- R3
- R6
- R7
- R11
- R13
- R19
- R24

### Полезные на второй итерации
- R5
- R12
- R15
- R17
- R18
- R21
- R22
- R23

### Полезные, если соберетесь в graph direction
- R16
- R24
- R25
