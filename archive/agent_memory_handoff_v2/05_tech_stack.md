# 05. Technical Recommendations

## 1. Recommended default stack

### Storage
- **Postgres 16+**
- **pgvector**
- `JSONB`
- full-text indexes
- optional Redis for queues/cache

### Application layer
- **Python 3.12+**
- **FastAPI**
- **Pydantic v2**
- **SQLAlchemy 2.x + Alembic**
- `psycopg` 3.x

### Background jobs
- **Temporal** если нужен надежный workflow engine
- **Celery** / **RQ** / **Arq** если хочется проще

### Blob / document storage
- **S3** / **MinIO**

### Observability
- **OpenTelemetry**
- **Prometheus + Grafana**
- structured logs
- trace IDs in `memory_runs`

## 2. Почему я все еще рекомендую Postgres + pgvector

Потому что здесь в одном месте удобно держать:

- entities
- notes
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

`Note` слой не ломает этот выбор. Наоборот, он хорошо живет в том же Postgres:
- `notes` как typed table
- `note_tsv` для lexical retrieval
- `embedding` для semantic retrieval
- `note_links` для graph-shaped navigation

## 3. Suggested architecture by maturity

### Option A — Lean assistant MVP
- Postgres + pgvector
- FastAPI
- Pydantic structured outputs
- one memory worker
- claims + profile + tasks
- no notes yet

Плюсы:
- быстро стартует
- мало инфраструктуры
- удобно дебажить

Минусы:
- rationale / direction memory будут беднее

---

### Option B — Knowledge-work MVP
- все из Option A
- `notes` layer
- note retrieval
- note reconciliation
- background note consolidation

Плюсы:
- лучше для design discussions и doc-heavy контекста
- меньше forcing pseudo-facts
- лучше ответы на “почему решили / к чему склонялись”

Минусы:
- чуть сложнее data model и prompts
- нужна дисциплина, чтобы не получить note-spam

---

### Option C — Scaled retrieval
- Postgres as source of truth
- Qdrant for vector-heavy retrieval
- dedicated retrieval service
- background sync from Postgres to Qdrant

Плюсы:
- лучше под большую retrieval нагрузку
- легче отдельно тюнить vector layer

Минусы:
- dual-write / sync complexity
- больше infra

---

### Option D — Graph-heavy product
- Postgres as write source of truth
- graph projection to Neo4j
- vector store optional
- typed memory pipeline remains unchanged

Плюсы:
- можно добавить graph use cases без полного переписывания write path

Минусы:
- две read models
- сложнее consistency и observability

## 4. Когда нужен Qdrant

Идти в Qdrant стоит, если:
- corpus растет очень быстро
- filtered ANN стал bottleneck
- retrieval доминирует над structured updates
- note/claim/source retrieval стал отдельной производительной задачей

Хороший split:
- Postgres: source of truth для entities / notes / claims / tasks / profiles
- Qdrant: retrieval index для sources / notes / claims / episodes

## 5. Когда нужен Neo4j

Neo4j имеет смысл, если ядро продукта — это:
- path queries
- multi-hop traversal
- dependency graphs
- knowledge graph exploration UI
- recommendation / impact analysis
- graph-native analytics

Когда он **не нужен**:
- upsert facts
- versioning
- temporal filtering
- source lineage
- profile/task projection
- обычный retrieval для agent answers

В этом случае graph-shaped schema в Postgres обычно проще и дешевле.

## 6. Recommended model strategy

### Separate models by job
Не надо заставлять один и тот же model tier делать всё.

#### Small / cheap model
Использовать для:
- router
- write-time retrieval planning
- read-time retrieval planning
- fast/slow escalation gate
- maybe light entity canonicalization

#### Stronger structured-output model
Использовать для:
- note construction
- note reconciliation
- claim extraction
- claim reconciliation
- profile updates
- task updates
- note consolidation

#### Main answer model
Использовать отдельно от memory worker.
Это снижает связность между quality ответа и quality memory maintenance.

## 7. Fast / slow path — рекомендуемая следующая эволюция

Если система становится сложнее, я бы делал:

### Fast path
- дешёвая/быстрая модель
- user-facing
- simple retrieval
- cheap drafting
- escalation gate

### Slow path
- сильная модель
- reasoning / verification / conflict resolution
- note consolidation
- memory repair
- high-stakes answers

Важно:
- между fast и slow path лучше гонять **typed handoff objects**, а не свободный бесконечный текст
- slow path не обязан быть отдельным “агентом” в UX-смысле; это может быть просто внутренний worker

## 8. Structured outputs are non-negotiable

На всех critical memory steps я бы требовал:
- JSON schema
- validation
- retries with validation feedback

Иначе основная боль будет не “модель плохо думает”, а “пайплайн разваливается на форме данных”.

## 9. Suggested query/index strategy

### Postgres indexes
Минимум:
- B-tree:
  - `(namespace_id, observed_at desc)` on `sources`
  - `(namespace_id, entity_type, normalized_name)` on `entities`
  - `(namespace_id, note_type, status)` on `notes`
  - `(namespace_id, subject_entity_id, predicate, status)` on `claims`
  - `(namespace_id, status, due_at)` on `tasks`
- GIN:
  - `metadata_json`
  - `qualifiers_json`
  - `tags_json`
  - `keywords_json`
  - `to_tsvector` on `content_text`, `summary`, `normalized_text`
- HNSW / IVFFlat:
  - `embedding` on `sources`
  - `embedding` on `notes`
  - `embedding` on `claims`
  - `embedding` on `episodes`

### Retrieval pattern
Я бы использовал hybrid retrieval:
- lexical / tsvector
- vector similarity
- structured filters
- recency / importance rerank
- optional entity overlap boost

Для `Note` это особенно важно, потому что rationale-queries часто лучше ловятся не только векторами, но и lexical overlap.

## 10. Recommended package boundaries

### Memory service should own
- source persistence
- retrieval planning
- entity canonicalization
- note/claim/task/profile ops
- lineage tables
- memory run audit
- consolidation jobs

### Runtime should own
- user dialogue
- answer composition
- tool orchestration
- calling memory read/write APIs
- fast/slow escalation decision

## 11. Practical implementation notes

- размер `VECTOR(n)` подстрой под свою embedding model
- для earliest MVP можно обойтись без отдельного retrieval service
- `memory_runs` и shadow mode сэкономят много боли при апдейтах prompt'ов
- если note quality поплывет, первым делом режь over-generation и поднимай порог `noop`
- если claim quality поплывет, сначала смотри retrieval-before-update и predicate rules, а не только prompt wording

## 12. Что я бы выбрал для старта

Если бы я реально отдавал это в разработку сегодня:

- Postgres + pgvector
- FastAPI
- Pydantic v2
- SQLAlchemy 2.x
- one memory worker
- hybrid retrieval inside the same app
- Option B (with notes) для knowledge-work агента
- fast/slow path — только вторым этапом
