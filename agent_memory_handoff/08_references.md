# 08. References

Ниже — короткий список источников, на которые имеет смысл смотреть разработчику.
Это не “обязательная литература”, а быстрый map of the territory.

## Product / framework docs

### [R1] LangMem — Conceptual Guide
Long-term Memory in LLM Applications
https://langchain-ai.github.io/langmem/concepts/conceptual_guide/

Полезно для:
- semantic / episodic / procedural split
- memory subsystem framing

### [R2] LangMem — Background Quickstart
Background Quickstart Guide
https://langchain-ai.github.io/langmem/background_quickstart/

Полезно для:
- background memory processing
- hot path vs background path

### [R3] LangMem — Delayed Background Processing
Delayed Background Memory Processing
https://langchain-ai.github.io/langmem/guides/delayed_processing/

Полезно для:
- debounce pattern
- delayed consolidation

### [R4] Letta — Memory Blocks
Memory blocks (core memory)
https://docs.letta.com/guides/core-concepts/memory/memory-blocks/

Полезно для:
- always-in-context memory blocks

### [R5] Letta — Archival Memory
Archival memory
https://docs.letta.com/guides/core-concepts/memory/archival-memory/

Полезно для:
- searchable memory outside prompt
- on-demand retrieval

### [R6] LangChain — Multi-agent / Subagents
Subagents
https://docs.langchain.com/oss/python/langchain/multi-agent/subagents

Полезно для:
- supervisor / worker pattern
- optional fast/slow split

## Papers / benchmarks

### [R7] Mem0
Mem0: Building Production-Ready AI Agents with Scalable Long-Term Memory
https://arxiv.org/abs/2504.19413

Полезно для:
- extraction + update pipeline
- practical production framing

### [R8] A-MEM
A-MEM: Agentic Memory for LLM Agents
https://arxiv.org/abs/2502.12110

Полезно для:
- note-like structured memory
- linked memory evolution

### [R9] LongMemEval
LongMemEval: Benchmarking Chat Assistants on Long-Term Interactive Memory
https://arxiv.org/abs/2410.10813

Полезно для:
- evaluation buckets
- temporal reasoning
- knowledge updates
- abstention

### [R10] LoCoMo
Evaluating Very Long-Term Conversational Memory of LLM Agents
https://arxiv.org/abs/2402.17753

Полезно для:
- long multi-session dialogue benchmark
- event-based memory testing

### [R11] LightMem
LightMem: Lightweight and Efficient Memory-Augmented Generation
https://arxiv.org/abs/2604.07798

Полезно для:
- online vs offline memory split
- bounded memory processing
- compaction intuition

### [R12] SimpleMem
SimpleMem: Simple, Query-aware Memory for Long-Horizon Agents
https://arxiv.org/abs/2601.02553

Полезно для:
- budget-aware retrieval intuition
- query-shaped memory selection

### [R13] FluxMem / Choosing How to Remember
Choosing How to Remember: Adaptive Memory Structure Selection for LLM Agents
https://arxiv.org/abs/2602.14038

Полезно для:
- adaptive memory path selection
- not all memory should use the same structure

## Storage / infra docs

### [R14] pgvector
pgvector for Postgres
https://github.com/pgvector/pgvector

Полезно для:
- vectors inside Postgres
- ANN + exact search
- joins with structured data

### [R15] Qdrant — Filtering
Filtering
https://qdrant.tech/documentation/search/filtering/

Полезно для:
- metadata-aware vector retrieval
- filtered search patterns

### [R16] Qdrant — Search
Search
https://qdrant.tech/documentation/search/search/

Полезно для:
- score thresholds
- filtered ANN basics

### [R17] Neo4j — Graph database concepts
Graph database concepts
https://neo4j.com/docs/getting-started/appendix/graphdb-concepts/

Полезно для:
- graph-native thinking
- понимание, когда graph DB действительно нужен

## Что я бы реально прочитал перед разработкой

Обязательный минимум:
- R1
- R3
- R4
- R5
- R7
- R8
- R9
- R11
- R14

Если планируешь fast/slow path:
- R6

Если хочешь сильнее продумать bounded retrieval и note lifecycle:
- R11
- R12
- R13

Если думаешь про separate vector layer:
- R15
- R16

Если думаешь про graph direction:
- R17
