# 05. Technical Recommendations

## 1. Recommended default stack

### Storage
- **Postgres 16+**
- **pgvector**
- `JSONB`
- full-text indexes
- optional Redis for short-lived queues/cache

### Application layer
- **Python 3.12+**
- **FastAPI**
- **Pydantic v2**
- **SQLAlchemy 2.x + Alembic**

### Background jobs
- **Temporal** if нужен надежный workflow engine
- **Celery** или **RQ** если хочется проще и дешевле

### Blob / document storage
- **S3** / **MinIO**

### Observability
- **OpenTelemetry**
- **Prometheus + Grafana**
- structured logs

## 2. Почему я рекомендую Postgres + pgvector как старт

Потому что здесь в одном месте удобно держать:
- entities
- claims
- tasks
- profile projection
- evidence/source log
- vector search
- metadata filters
- transactions
- joins и lineage

Для memory layer это обычно лучший trade-off:
- проще ops
- проще consistency
- проще debugging
- меньше moving parts

## 3. Когда нужен Qdrant

Идти в Qdrant стоит, если:
- memory corpus очень быстро растет
- vector search и filtered ANN стали bottleneck
- retrieval сильно доминирует над structured updates
- хочется вынести vector workload из Postgres

### Хороший practical split
- Postgres: source of truth для entities / claims / tasks / profiles
- Qdrant: retrieval index для sources / claims / episodes

Такой split нормален, если vector-нагрузка реально большая.

## 4. Когда нужен Neo4j

Neo4j имеет смысл, если у тебя ядро продукта — это:
- path queries
- multi-hop traversal
- dependency graphs
- graph algorithms
- knowledge graph exploration UI
- recommendation / impact analysis

### Когда точно не нужен
Если текущие основные операции такие:
- upsert facts
- versioning
- temporal filtering
- source lineage
- profile/task projection
- simple retrieval for agent answers

В этом случае graph-shaped schema в Postgres обычно проще и дешевле.

## 5. Suggested architecture by maturity

### Option A — Recommended MVP
- Postgres + pgvector
- FastAPI
- Pydantic structured outputs
- one memory worker
- optional Celery/RQ
- one retrieval service inside app

Плюсы:
- быстро стартует
- мало инфраструктуры
- удобная транзакционность

Минусы:
- если retrieval нагрузка вырастет, может понадобиться split

---

### Option B — Scaled retrieval
- Postgres for source of truth
- Qdrant for vector search
- dedicated retrieval service
- background sync from Postgres to Qdrant

Плюсы:
- лучше под большую retrieval нагрузку
- легче тюнить vector layer отдельно

Минусы:
- dual-write / sync complexity
- больше infra

---

### Option C — Graph-heavy product
- Postgres as write source of truth
- graph projection to Neo4j
- vector store optional
- typed memory pipeline remains unchanged

Плюсы:
- можно добавить graph use cases без полного переписывания write path

Минусы:
- две read models
- сложнее observability и consistency

## 6. Recommended model strategy

### Separate models by job
Не надо заставлять один и тот же model tier делать все.

#### Small/cheap model
Использовать для:
- router
- retrieval planning
- maybe simple compaction triage

#### Stronger structured-output model
Использовать для:
- claim extraction
- claim reconciliation
- profile updates
- task updates

#### Main answer model
Использовать отдельно от memory worker.
Это снижает связность между quality ответа и quality memory maintenance.

## 7. Structured outputs are non-negotiable

На всех critical memory steps я бы требовал:
- JSON schema
- validation
- retries with validation error feedback

Иначе основная боль будет не “модель плохо думает”, а “пайплайн разваливается на форме данных”.

## 8. Suggested table/index strategy

### Postgres indexes
Минимум:
- B-tree:
  - `(namespace_id, observed_at desc)` on `sources`
  - `(namespace_id, normalized_name)` on `entities`
  - `(namespace_id, subject_entity_id, predicate, status)` on `claims`
  - `(namespace_id, status, due_at)` on `tasks`
- GIN:
  - `metadata_json`
  - `qualifiers_json`
  - optionally `to_tsvector` on `normalized_text`
- HNSW / IVFFlat:
  - `embedding` on `sources`
  - `embedding` on `claims`
  - `embedding` on `episodes`

### Notes
- HNSW обычно удобен для read-heavy ANN
- filtered retrieval все равно требует думать про metadata indexes
- текстовые exact-ish filters не надо заменять embeddings

## 9. Suggested API surface

### Write-side
- `POST /sources`
- `POST /memory/runs/process`
- `POST /memory/compact`

### Read-side
- `POST /memory/search`
- `GET /profile/{owner_id}`
- `GET /tasks/open/{owner_id}`
- `POST /memory/retrieve-for-answer`

### Admin / eval
- `GET /memory/runs/{run_id}`
- `POST /eval/replay`
- `GET /metrics`

## 10. Suggested internal modules

```text
app/
  api/
  domain/
    entities.py
    claims.py
    tasks.py
    profiles.py
  prompts/
    router.py
    retrieval_planner.py
    claim_extractor.py
    claim_reconciler.py
    profile_updater.py
    task_updater.py
  services/
    source_ingest.py
    memory_update.py
    retrieval.py
    compaction.py
  repositories/
    source_repo.py
    entity_repo.py
    claim_repo.py
    task_repo.py
    profile_repo.py
    run_repo.py
  workers/
  eval/
```

## 11. Recommended repository boundaries

### Source repository
- append source
- fetch by source ids
- retrieve evidence chunks

### Entity repository
- lookup by alias
- upsert entity
- merge alias/entity

### Claim repository
- retrieve by subject/predicate/time
- vector + lexical candidate search
- apply reconcile ops

### Profile repository
- get current profile
- upsert projection
- compact/refresh

### Task repository
- retrieve open tasks
- apply task ops

### Run repository
- persist audit logs
- fetch for debugging/eval

## 12. Prompt storage and versioning

Промпты надо хранить как артефакты, а не только в коде строками.

### Suggested practice
- `prompt_name`
- `prompt_version`
- template file in git
- migration note when behavior changes
- run log references exact version

Иначе невозможно честно сравнивать качество.

## 13. Suggested ranking formula for retrieval

На уровне идеи:
- semantic similarity
- lexical similarity
- exact subject/predicate match boost
- recency
- importance
- status boost (`active > superseded > retracted`)
- temporal overlap boost
- usage boost (`last_used_at`, `use_count`)

Пример:
```text
score =
  0.35 * semantic
+ 0.20 * lexical
+ 0.20 * exact_structural_match
+ 0.10 * recency
+ 0.10 * importance
+ 0.05 * usage
```

Это не истина, а хороший старт.

## 14. Deployment options

### Single service + worker
Нормально для MVP:
- one API app
- one worker
- one Postgres
- optional Redis

### Split retrieval service
Нормально, если retrieval быстро растет:
- API
- memory worker
- retrieval service
- Postgres
- optional Qdrant

### Managed infra
Если хочется меньше ops:
- managed Postgres
- managed object storage
- managed queue/workflow
- app containers on обычной platform-as-a-service

## 15. Security and compliance notes

Минимум я бы сделал:
- tenant namespace isolation
- audit trail for memory changes
- delete/forget endpoint
- PII tagging in `qualifiers_json`
- raw evidence retention policy
- encryption at rest for DB and object storage

## 16. What I would not optimize too early

Не тратить много времени в начале на:
- super-advanced ANN tuning
- graph DB migration
- distributed sharding
- fancy memory graphs for visualization
- autonomous prompt self-editing

Сначала должны заработать:
- clean writes
- stable reconciliation
- useful retrieval
- honest abstention

## 17. Practical build order

### Sprint 1
- Postgres schema
- source ingest
- router
- claims/profile/tasks prompts
- manual review UI/logs

### Sprint 2
- retrieval-before-update
- reconciliation
- task state transitions
- read-time retrieval

### Sprint 3
- compaction
- eval harness
- observability
- performance tuning

## 18. Final recommendation

Если говорить совсем прямо:
- **не начинай с графовой БД**
- **не начинай с vector-only памяти**
- **начни с Postgres + pgvector + typed memory + background worker**

Это почти всегда самый практичный и наименее болезненный старт.
