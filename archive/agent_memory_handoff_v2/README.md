# Agent Memory Blueprint v2

Этот пакет — обновленный хенд-офф для разработчика по реализации долговременной памяти у AI-агента.

Главное изменение относительно прошлой версии: добавлен слой `Note` как **селективная промежуточная память** между `Source` и жестко нормализованными `Claim`/`Profile`/`Task`.

Коротко про идею:

- `Source` — сырой evidence layer.
- `Claim` — атомарный нормализованный факт.
- `Note` — компактный self-contained memory fragment, который уже полезен, но еще не обязан быть жестким фактом.
- `Profile` и `Task` — проекции / operational memory.
- `Episode` — уже второй этап, когда понадобится переносимый опыт.

Ключевая позиция пакета теперь такая:

- **Начинать с Postgres + pgvector**, а не с graph DB.
- **Моделировать память как typed memory**, а не как набор speech acts.
- **Добавлять `Note` для design discussions, rationale, evolving plans, lessons и doc digests**.
- **Не заставлять каждый write идти через `Note`**. Для явных фактов, задач и устойчивых preferences остается прямой путь `Source -> Claim/Profile/Task`.
- **Делать retrieval + reconciliation перед update**, а не просто append.
- **Держать маленький core memory block в prompt**, а остальное доставать поиском.
- **Выносить тяжелую memory maintenance логику в slow/background path**.

## Что внутри

- `01_architecture.md` — общая архитектура, storage tiers и optional fast/slow path
- `02_data_model.md` — сущности, поля, инварианты и смысл `Note`
- `03_prompts.md` — обновленный prompt pack
- `04_workflows.md` — write/read/background workflow
- `05_tech_stack.md` — рекомендуемый стек и варианты эволюции
- `06_schema.sql` — стартовый DDL для Postgres + pgvector
- `07_eval_plan.md` — как это тестировать
- `08_references.md` — ориентиры по docs / papers / infra

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

В этом варианте `Note` можно отложить.

### Вариант B — knowledge-work MVP
Подходит, если агент будет жить рядом с:
- архитектурными обсуждениями
- evolving plans
- doc-heavy контекстом
- rationale / trade-offs
- lessons learned

Минимум:
- все из варианта A
- `notes`
- `note_entities`
- `note_sources`
- `note_links`
- `note_consolidator` в фоне

Для твоего кейса “агент сохраняет знания в базу” я бы уже **сейчас** смотрел в сторону варианта B.

## Что не делать

- не сохранять каждую реплику как knowledge item
- не хранить память только как embeddings без typed records
- не пытаться одной LLM-call одновременно классифицировать, извлекать, канонизировать, дедупить и инвалидировать
- не превращать design discussion в кучу псевдо-фактов
- не использовать `Note` как свалку summary-шума
- не хранить raw chain-of-thought

## Главные инженерные принципы

1. **Evidence is immutable, memory is revisable.**
   Источники почти append-only; notes/claims/tasks/profile могут обновляться и supersede'иться.

2. **One memory item = one reusable unit.**
   Один claim — одно атомарное утверждение.
   Одна note — один переиспользуемый фрагмент смысла.
   Один task — одно обязательство.

3. **`Note` — не source of truth.**
   Это промежуточный semantic layer для вещей, которые уже важны, но еще не хочется цементировать как hard fact.

4. **Time is first-class.**
   Относительные даты нормализуем при записи. В памяти важно не только *что*, но и *когда это было истинно*.

5. **Lineage обязателен.**
   У notes/claims/tasks должны быть ссылки на evidence и/или origin note.

## Быстрый план внедрения

### Phase 1
- Postgres + pgvector
- claims
- profile
- tasks
- read-time retrieval
- write-time reconciliation
- basic eval harness

### Phase 1.5
- notes
- note retrieval
- rationale answers
- note-specific prompts
- note consolidation в фоне

### Phase 2
- episodes
- richer predicate catalog
- alias merge
- duplicate cleanup
- optional Qdrant или graph read-model

### Phase 3
- slow/fast execution path
- explicit escalation gate
- offline reflection
- temporal query expansion
- graph projection, если реально понадобится

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
- на **отдельный slow memory manager**, если hot path начал страдать от сложного reconciliation

## Минимальный контракт между runtime и memory layer

### На запись
Runtime / worker должен уметь:
- сохранить `source`
- решить `noop / direct_structured_write / note_write / mixed`
- найти candidate memories
- сделать canonicalization
- применить `insert/update/supersede/retract/noop`
- записать audit trail

### На чтение
Runtime должен уметь:
- решить, нужен ли memory search
- подтянуть core blocks
- выбрать `claim-first` или `note-first` retrieval
- уметь сходить до raw evidence при конфликте
- отвечать с учетом uncertainty
