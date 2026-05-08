# Agent Memory Blueprint

Этот пакет — хенд-офф для разработчика по реализации долговременной памяти у AI-агента.

Базовая позиция пакета такая:

- **Начинать не с graph DB, а с Postgres + pgvector**.
- **Моделировать память как typed memory**, а не как набор speech acts.
- **Хранить отдельно сырой источник, нормализованные memory-записи и retrieval-индексы**.
- **Обновлять память через retrieval + reconciliation**, а не просто append.
- **Держать маленький core memory block в prompt**, а остальное доставать поиском.
- **Делать часть записи в фоне**, чтобы не портить latency ответа.

## Что внутри

- `01_architecture.md` — общая архитектура и storage tiers
- `02_data_model.md` — сущности, поля, инварианты и predicate catalog
- `03_prompts.md` — готовый набор промптов
- `04_workflows.md` — write/read/background workflow
- `05_tech_stack.md` — рекомендуемый стек и варианты эволюции
- `06_schema.sql` — стартовый DDL для Postgres + pgvector
- `07_eval_plan.md` — как это тестировать и не утонуть в галлюцинациях памяти
- `08_references.md` — источники и papers, на которые опирался дизайн

## Рекомендуемый MVP

Если делать без перегруза, MVP я бы ограничил так:

1. `sources`
2. `entities`
3. `claims`
4. `claim_sources`
5. `profiles`
6. `tasks`
7. `memory_runs`

Плюс:
- один background memory worker
- retrieval по `claims` и `sources`
- core memory block из `profile + active tasks + tiny session summary`
- prompt pack из файлов `03_prompts.md`

## Что не делать в первой версии

- не сохранять каждую реплику как knowledge item
- не пытаться одной LLM-call одновременно классифицировать, извлекать, канонизировать, дедупить и инвалидировать
- не хранить память только как embeddings без typed records
- не начинать с Neo4j, если у продукта пока нет тяжелых multi-hop traversal use cases
- не хранить raw chain-of-thought как episodic memory

## Главные инженерные принципы

1. **Evidence is immutable, memory is revisable.**
   Источники почти append-only; claims/tasks/profile могут обновляться и supersede'иться.

2. **One memory item = one reusable unit.**
   Один claim — одно атомарное утверждение. Один task — одно обязательство. Один episode — один переносимый урок.

3. **Every memory item needs lineage.**
   У каждого memory item должны быть `source_refs`, `confidence`, `valid_*`, `status`.

4. **Time is first-class.**
   Относительные даты надо нормализовать при записи. В памяти важно не только *что*, но и *когда* это было истинно.

5. **Prefer projection over duplication.**
   `Profile` — это компактная проекция устойчивых facts/preferences, а не отдельный магический источник истины.

## Быстрый план внедрения

### Phase 1
- Postgres + pgvector
- профили
- claims
- tasks
- read-time retrieval
- write-time reconciliation
- basic eval harness

### Phase 2
- episodes
- background compaction
- richer predicate catalog
- entity alias merging
- optional Qdrant или graph read-model

### Phase 3
- offline reflection
- temporal query expansion
- richer benchmark suite
- graph projection, если реально нужна

## Минимальный контракт между агентом и memory layer

### На запись
Агент или memory worker должен уметь:
- сохранять `source`
- извлекать candidate facts/tasks/profile updates
- искать похожие/конфликтующие memory items
- применять `insert/update/retract/noop`
- писать audit trail

### На чтение
Агент должен уметь:
- решать, нужен ли memory search
- запрашивать профиль, активные задачи, claims, raw evidence
- фильтровать по времени/сущностям/типам
- отвечать с учетом conflicts и missing evidence

## Коротко о выборе хранилищ

По умолчанию:
- **Structured store:** Postgres
- **Vector search:** pgvector
- **Queue/workflows:** Temporal / Celery / RQ
- **Raw docs/blob:** S3 / MinIO
- **App layer:** Python + FastAPI + Pydantic

Когда менять:
- на **Qdrant**, если vector-нагрузка и filtered ANN стали bottleneck
- на **Neo4j**, если multi-hop graph traversal стал частью ядра продукта
- на **отдельный graph read-model**, если нужен граф, но запись все еще удобнее держать в Postgres

## Зачем есть и `claims`, и `profile`

Потому что это разные функции:
- `claims` — поисковая и explainable память
- `profile` — компактный always-in-context блок

Нормальный паттерн: `profile` собирается из `claims + explicit style instructions + lightweight summarization`.

## Зачем есть `predicate catalog`

Без этого reconciliation становится слишком хрупким.
У предикатов должны быть правила:
- cardinality: `single` или `multi`
- temporal policy: `atemporal`, `time_scoped`, `status_like`
- conflict policy: `replace`, `coexist`, `range_split`
- sync targets: идет ли факт в `profile`, в `task projection`, в `graph projection`

Эта штука резко упрощает промпты и делает update-политику предсказуемее.
