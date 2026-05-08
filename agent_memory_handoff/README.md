# Agent Memory Blueprint v3

Этот пакет — `v3` хенд-офф по долговременной памяти для AI-агента.

Главное изменение относительно `v2`:

- `Note` остается важным промежуточным semantic layer
- но теперь вокруг него добавлена **дисциплина жизненного цикла**
- память проектируется не только как `representation`, но и как:
  - `lifecycle`
  - `bounded retrieval`
  - `repair`
  - `retention / forgetting`

Коротко про идею:

- `Source` — сырой evidence layer
- `Claim` — атомарный нормализованный факт
- `Note` — self-contained semantic fragment для rationale, direction, evolving plans и doc-heavy контекста
- `Profile` / `Task` — operational projections
- `Episode` — переносимый опыт, позже
- `MemoryRun` — audit/debug слой

Теперь центральная позиция пакета такая:

- **Начинать с Postgres + pgvector**, а не с graph DB
- **Делать typed memory**, а не vector-only dump
- **Использовать два semantic path**:
  - `direct structured path`
  - `note path`
- **Не заставлять каждый write идти через `Note`**
- **Делать retrieval + reconciliation перед update**
- **Разделять `truth state` и `retrieval state`**
- **Вводить bounded retrieval budgets**
- **Держать repair и retention как first-class background workflows**

## Что внутри

- `01_architecture.md` — архитектурная идея, lifecycle, retrieval budgets, repair
- `02_data_model.md` — сущности, поля, structured scope, note maturity, retention
- `03_prompts.md` — prompt pack для записи, чтения, consolidation и repair
- `04_workflows.md` — write/read/background workflows
- `05_tech_stack.md` — pragmatic stack и эволюция
- `06_schema.sql` — стартовый DDL для Postgres + pgvector
- `07_eval_plan.md` — как проверять memory state, а не только ответы
- `08_references.md` — papers / docs / infra ориентиры

## Какой rollout я рекомендую

### Вариант A — lean assistant MVP
Подходит, если память пока в основном про:
- устойчивые preferences
- простые explicit facts
- open tasks

Минимум:
- `sources`
- `entities`
- `claims`
- `claim_sources`
- `profiles`
- `tasks`
- `task_sources`
- `memory_runs`

В этом варианте:
- `Note` можно отложить
- но retrieval budgets, retention и repair hooks я бы заложил сразу

### Вариант B — knowledge-work MVP
Подходит, если агент работает с:
- архитектурными обсуждениями
- evolving plans
- rationale / trade-offs
- doc-heavy контекстом
- lessons learned

Минимум:
- все из варианта A
- `notes`
- `note_entities`
- `note_sources`
- `note_links`
- `note_consolidator`
- `memory_repair`

Для твоего кейса я бы смотрел именно сюда.

## Что не делать

- не сохранять каждую реплику как memory item
- не хранить память только как embeddings
- не пытаться одной LLM-call делать routing, extraction, canonicalization, reconciliation и compaction
- не превращать каждую design discussion в кучу псевдо-facts
- не использовать `Note` как свалку summary-шума
- не держать retrieval без budget caps
- не считать `stale` и `superseded` items равноправными active memory
- не хранить raw chain-of-thought

## Главные инженерные принципы

1. **Evidence is immutable, memory is revisable.**
   Источники почти append-only; notes/claims/tasks/profile могут обновляться, supersede'иться, архивироваться и удаляться по policy.

2. **One memory item = one reusable unit.**
   Один claim — одно атомарное утверждение.
   Одна note — один semantic fragment.
   Один task — одно обязательство.

3. **Truth state и retrieval state — разные вещи.**
   `active/superseded/retracted/expired` отвечают за истинностный статус.
   `active/warm/cold/archived` отвечают за то, как память ищется и хранится.

4. **Scope должен быть не только текстовым.**
   `scope_text` полезен для человека, но рядом должен быть machine-readable `scope_json`.

5. **Retrieval должен быть bounded.**
   У каждого answer mode должен быть budget:
   - сколько claims
   - сколько notes
   - сколько tasks
   - сколько evidence snippets

6. **Repair обязателен.**
   Хорошая write path логика сама по себе не гарантирует здоровую память через месяц.

7. **Retention / forgetting — это часть дизайна, а не последующая уборка.**

## Быстрый план внедрения

### Phase 1
- Postgres + pgvector
- claims
- profile
- tasks
- read-time retrieval
- write-time reconciliation
- memory_runs
- retrieval budgets

### Phase 1.5
- notes
- note retrieval
- rationale answers
- note-specific prompts
- structured scope

### Phase 2
- note consolidation
- memory repair loop
- retention policy
- alias merge
- duplicate cleanup
- richer predicate catalog

### Phase 3
- episodes
- fast/slow path
- temporal query expansion
- optional Qdrant or graph read-model

## Коротко о storage choice

По умолчанию:
- **Structured store:** Postgres
- **Vector search:** pgvector
- **Queue/workflows:** Temporal / Celery / RQ
- **Raw docs/blob:** S3 / MinIO
- **App layer:** Python + FastAPI + Pydantic

Когда усложнять:
- на **Qdrant**, если filtered ANN стал bottleneck
- на **Neo4j**, если multi-hop traversal стал частью ядра продукта
- на **separate memory manager**, если hot path страдает от reconciliation / repair / consolidation

## Минимальный контракт между runtime и memory layer

### На запись
Runtime / worker должен уметь:
- сохранить `source`
- решить `noop / direct_structured_write / note_write / mixed`
- найти candidate memories
- сделать canonicalization
- применить `insert/update/supersede/retract/noop`
- запланировать consolidation / repair / retention
- записать audit trail

### На чтение
Runtime должен уметь:
- решить, нужен ли memory search
- подтянуть core blocks
- выбрать `claim-first / note-first / mixed / evidence-first`
- уважать retrieval budget
- ходить до raw evidence при конфликте
- отвечать с учетом uncertainty

### На забывание / удаление
Memory layer должен уметь:
- архивировать малоиспользуемые items
- soft-delete / hard-delete по policy
- удалять память по namespace / entity / source request
- не ломать lineage и audit сильнее, чем требуется policy

## В одну строку

`v3` — это не просто memory store и не просто note layer.
Это **lifecycle-aware, scope-aware, repairable memory system** с bounded retrieval и explainable updates.
