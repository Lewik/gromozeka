# 02. Data Model

## 1. Общая идея

Модель данных должна одновременно поддерживать:

- typed memory
- lineage
- temporal truth
- reconcilable updates
- точные факты и более мягкий semantic context

Поэтому модель делится на:

- **evidence layer** — `Source`
- **graph-shaped semantic layer** — `Entity`, `Claim`
- **rich intermediate layer** — `Note`
- **projections / operational memory** — `Profile`, `Task`, `Episode`
- **audit layer** — `MemoryRun`

## 2. Главные сущности

### 2.1 `Source`
Сырой источник знания.

Назначение:
- хранить chat turns, document chunks, tool outputs
- быть первичным evidence layer
- служить опорой для re-check и explainability

Рекомендуемые поля:
- `source_id: uuid`
- `namespace_id: text`
- `source_type: chat_turn | document_chunk | tool_output | imported_note | external_record`
- `conversation_id: text | null`
- `turn_id: text | null`
- `speaker_role: user | assistant | tool | system | external`
- `author_label: text | null`
- `content_text: text`
- `content_hash: text`
- `observed_at: timestamptz`
- `created_at: timestamptz`
- `metadata_json: jsonb`
- `embedding: vector | null`

Инварианты:
- source не должен silently изменяться
- dedupe через `content_hash` полезен
- `observed_at` хранится отдельно от ingestion time

---

### 2.2 `Entity`
Канонический узел памяти.

Назначение:
- объединять разные упоминания одного и того же объекта
- быть якорем для claims/tasks/notes/episodes
- поддерживать aliases и merge

Рекомендуемые поля:
- `entity_id: uuid`
- `namespace_id`
- `entity_type: person | agent | organization | project | repo | file | technology | product | location | concept | document | conversation | service`
- `canonical_name: text`
- `normalized_name: text`
- `summary: text | null`
- `status: active | merged | deleted`
- `merged_into_entity_id: uuid | null`
- `attributes_json: jsonb`
- `first_seen_at: timestamptz`
- `last_seen_at: timestamptz`
- `created_at: timestamptz`
- `updated_at: timestamptz`

Отдельная таблица `entity_aliases`:
- `alias_id`
- `entity_id`
- `alias_text`
- `alias_normalized`
- `source_id`
- `confidence`
- `created_at`

Инварианты:
- merge не удаляет историю
- alias всегда указывает на текущий canonical entity
- `normalized_name` индексируется

---

### 2.3 `PredicateCatalog`
Это не memory item, а конфиг для reconciliation.

Почему нужен:
- у разных предикатов разная cardinality
- у разных предикатов разная temporal semantics
- одни факты заменяются, другие сосуществуют
- часть facts должна попадать в profile projection

Рекомендуемые поля:
- `predicate: text primary key`
- `description: text`
- `subject_type: text`
- `object_kind: entity | string | number | boolean | json`
- `cardinality: single | multi`
- `temporal_policy: atemporal | time_scoped | status_like`
- `conflict_policy: replace | coexist | range_split`
- `profile_sync: boolean`
- `task_sync: boolean`
- `default_importance: smallint`
- `active: boolean`

Примеры:
- `preferred_name`
- `speaks_language`
- `prefers_response_language`
- `prefers_code_comment_language`
- `works_with`
- `has_goal`
- `works_on_project`
- `uses_database`
- `has_constraint`

---

### 2.4 `Note`
Промежуточный semantic memory fragment.

Это **не source**, **не final claim**, **не chain-of-thought**.

`Note` нужен для вещей, которые:
- уже важны
- полезны для retrieval
- но еще не должны быть принудительно зацементированы как атомарный факт

Типичные кейсы:
- design direction
- decision rationale
- evolving plans
- hypotheses
- lessons / tactics / patterns
- doc digests
- contextual framing

Рекомендуемые поля:
- `note_id: uuid`
- `namespace_id`
- `note_type: decision | direction | hypothesis | plan | lesson | doc_digest | context`
- `title: text`
- `summary: text`
- `scope_text: text | null`
- `status: active | superseded | retracted | resolved | stale | candidate`
- `anchor_entity_id: uuid | null`
- `keywords_json: jsonb`
- `tags_json: jsonb`
- `candidate_claims_json: jsonb`
- `metadata_json: jsonb`
- `confidence: numeric(4,3)`
- `importance: smallint`
- `valid_from: timestamptz | null`
- `valid_to: timestamptz | null`
- `supersedes_note_id: uuid | null`
- `created_from_run_id: uuid | null`
- `created_at: timestamptz`
- `updated_at: timestamptz`
- `embedding: vector | null`
- `use_count: integer`
- `last_used_at: timestamptz | null`

Дополнительные таблицы:

#### `note_entities`
Нормализованная привязка notes к сущностям.
- `note_id`
- `entity_id`
- `entity_role: primary | secondary | mentioned | owner | subject`

#### `note_sources`
Lineage notes.
- `note_id`
- `source_id`
- `support_type: direct | summarized | imported`
- `evidence_span`
- `evidence_quote`
- `support_confidence`

#### `note_links`
Связи между notes.
- `from_note_id`
- `to_note_id`
- `link_type: supports | contradicts | refines | related | supersedes | derived_from`
- `link_weight`

Инварианты `Note`:
- одна note = один переиспользуемый semantic fragment
- note должна быть понятна без исходного чата
- note не дублирует source без added value
- `candidate_claims_json` — это только hints, а не source of truth
- `Note` можно хранить годами, даже если он так и не стал claim, если он полезен для retrieval

Пример `Note`:
```json
{
  "note_type": "decision",
  "title": "Use Postgres as memory source of truth",
  "summary": "The current direction is to keep Postgres with pgvector as the main memory store because it keeps structured memory, retrieval metadata, and transactions together. Neo4j is deferred until traversal-heavy use cases become core.",
  "scope_text": "Agent memory layer for the current product iteration",
  "status": "active",
  "keywords_json": ["postgres", "pgvector", "memory-layer"],
  "tags_json": ["architecture", "storage"],
  "confidence": 0.88,
  "importance": 8
}
```

---

### 2.5 `Claim`
Атомарный факт или relation edge.

Это ключевая таблица для точных ответов.

Рекомендуемые поля:
- `claim_id: uuid`
- `namespace_id`
- `subject_entity_id: uuid`
- `predicate: text`
- `object_entity_id: uuid | null`
- `object_value_json: jsonb | null`
- `normalized_text: text`
- `context_text: text | null`
- `qualifiers_json: jsonb`
- `confidence: numeric(4,3)`
- `importance: smallint`
- `status: active | superseded | retracted | expired | candidate`
- `valid_from: timestamptz | null`
- `valid_to: timestamptz | null`
- `origin_note_id: uuid | null`
- `first_seen_at: timestamptz`
- `last_seen_at: timestamptz`
- `supersedes_claim_id: uuid | null`
- `retracted_by_claim_id: uuid | null`
- `created_from_run_id: uuid | null`
- `created_at: timestamptz`
- `updated_at: timestamptz`
- `embedding: vector | null`
- `use_count: integer`
- `last_used_at: timestamptz | null`

`object_entity_id` и `object_value_json` — взаимоисключающие.

Инварианты:
- один claim = один predicate application
- claim должен иметь смысл без исходного чата
- explicit statement > inference
- новая explicit информация может supersede старый claim
- если информация еще слишком мягкая — лучше `Note`, чем плохой `Claim`

Отдельная таблица `claim_sources`:
- `claim_id`
- `source_id`
- `support_type: direct | inferred | imported | derived_from_note`
- `evidence_span`
- `evidence_quote`
- `support_confidence`

Примеры:
- `(user_1, prefers_response_language, "ru")`
- `(user_1, prefers_code_comment_language, "en")`
- `(user_1, works_with, python_entity)`

---

### 2.6 `Profile`
Компактная always-in-context проекция.

Это **не магический источник истины**, а projection layer поверх:
- explicit user instructions
- stable claims
- selected long-lived context

Рекомендуемые поля:
- `profile_id: uuid`
- `namespace_id`
- `owner_entity_id: uuid`
- `profile_json: jsonb`
- `profile_text: text`
- `version: bigint`
- `updated_from_run_id: uuid | null`
- `last_compacted_at: timestamptz | null`
- `created_at`
- `updated_at`

Рекомендуемая структура `profile_json`:
```json
{
  "preferred_name": null,
  "languages": [],
  "timezone": null,
  "response_preferences": {
    "response_language": null,
    "tone": null,
    "detail_level": null,
    "code_comment_language": null
  },
  "expertise": [],
  "stable_preferences": [],
  "constraints": [],
  "current_goals": [],
  "soft_signals": []
}
```

Инварианты:
- profile должен быть коротким
- explicit facts/claims важнее inference
- transient mood обычно не должен попадать в profile
- если факт спорный, лучше оставить его в claim/note, а не тащить в profile

---

### 2.7 `Task`
Operational memory про будущее действие.

Задача нужна только когда действительно требуется future action.

Рекомендуемые поля:
- `task_id: uuid`
- `namespace_id`
- `owner_entity_id: uuid | null`
- `assignee_entity_id: uuid | null`
- `title: text`
- `description: text | null`
- `status: open | in_progress | blocked | done | cancelled`
- `priority: low | normal | high`
- `due_at: timestamptz | null`
- `acceptance_criteria_json: jsonb`
- `blockers_json: jsonb`
- `related_entity_ids_json: jsonb`
- `origin_note_id: uuid | null`
- `created_from_run_id: uuid | null`
- `confidence: numeric(4,3)`
- `created_at`
- `updated_at`
- `closed_at`
- `use_count`
- `last_used_at`

Отдельная таблица `task_sources`:
- `task_id`
- `source_id`
- `support_type: direct | derived_from_note | imported`
- `evidence_span`
- `evidence_quote`
- `support_confidence`

Инварианты:
- plain question != task
- request != commitment
- один task = одно понятное обязательство
- status должен обновляться, а не плодить дубликаты

---

### 2.8 `Episode`
Опциональный тип памяти для переносимого опыта.

Нужен не в MVP, а позже — когда хочется хранить:
- успешные tactics
- failure patterns
- reusable lessons

Рекомендуемые поля:
- `episode_id`
- `namespace_id`
- `owner_entity_id`
- `situation`
- `action`
- `result`
- `lesson`
- `tags_json`
- `success_score`
- `origin_note_id`
- `created_from_run_id`
- `source_refs_json`
- `created_at`
- `updated_at`
- `embedding`
- `use_count`
- `last_used_at`

Инварианты:
- не хранить raw chain-of-thought
- episode — это lesson, а не полный внутренний reasoning trace

---

### 2.9 `MemoryRun`
Audit trail и дебаг слой.

Нужен, чтобы видеть:
- какой prompt сработал
- на какой модели
- с каким input hash
- какие ops были предложены
- какие ops реально применились
- где пайплайн сломался

Рекомендуемые поля:
- `run_id`
- `namespace_id`
- `run_type: route | retrieve_update | canonicalize | construct_notes | reconcile_notes | extract_claims | reconcile_claims | update_profile | update_tasks | consolidate_notes | read_plan | compose_answer | compact`
- `trigger_mode: hot_path | background | manual | slow_path`
- `source_ids_json`
- `prompt_name`
- `prompt_version`
- `model_name`
- `input_hash`
- `output_json`
- `applied_ops_json`
- `latency_ms`
- `token_input`
- `token_output`
- `status: success | failed | partial`
- `error_text`
- `created_at`

Инварианты:
- run log не должен исчезать
- `input_hash` полезен для идемпотентности и retry safety

## 3. Почему `Note` не заменяет `Claim`

Потому что у них разная работа.

### `Claim`
Нужен для:
- точных factual answers
- reconciliation через predicate rules
- explainable query results
- status/pref updates

### `Note`
Нужен для:
- richer retrieval
- decision history
- rationale
- partial or evolving meaning
- later consolidation

Если пытаться хранить все как `Claim`, получится ложная точность.
Если пытаться хранить все как `Note`, получится слишком мягкая и плохо reconcile'ящаяся память.

## 4. Рекомендуемые field descriptions для Pydantic / JSON schema

Эта штука сильно влияет на качество structured output.

### Для `Note`
- `title`: "Short label for the memory note. Must be understandable without the original conversation."
- `summary`: "Self-contained semantic fragment. Capture what matters and keep uncertainty explicit."
- `scope_text`: "What this note applies to and what boundaries prevent over-generalization."
- `candidate_claims_json`: "Optional hints for later normalization. Do not force claims when the evidence is still soft."

### Для `Claim`
- `normalized_text`: "Stand-alone statement that can be shown to another model or a human without the original chat."
- `qualifiers_json`: "Structured scope, modality, and additional constraints that make the claim precise."
- `valid_from`: "Absolute start time when the claim becomes true, if known."

### Для `Task`
- `acceptance_criteria_json`: "Observable conditions for considering the task complete."
- `blockers_json`: "Known blockers that prevent progress right now."

### Для `Profile`
- `soft_signals`: "Weakly inferred stable tendencies. Keep them low confidence or omit them."

## 5. Модель данных в одной строке

- `Source` = что было сказано / увидено
- `Entity` = кто/что это вообще
- `Note` = что уже важно и полезно, но еще не хочется цементировать как факт
- `Claim` = что мы считаем нормализованным фактом
- `Profile` = компактная проекция устойчивого контекста
- `Task` = что должно быть сделано
- `Episode` = чему система научилась
- `MemoryRun` = как именно память обновлялась
