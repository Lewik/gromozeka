# 02. Data Model

## 1. Общая идея

Модель данных в `v3` должна одновременно поддерживать:

- typed memory
- lineage
- temporal truth
- reconcilable updates
- richer semantic context
- lifecycle / retention
- repairability

Поэтому модель делится на:

- **evidence layer** — `Source`
- **graph-shaped semantic layer** — `Entity`, `Claim`
- **rich intermediate layer** — `Note`
- **projections / operational memory** — `Profile`, `Task`, `Episode`
- **audit layer** — `MemoryRun`

Плюс у `v3` есть два cross-cutting concern:

- `scope`
- `retrieval lifecycle`

## 2. Общие cross-cutting поля

### 2.1 `scope_text` и `scope_json`

Почти любой memory item, кроме сырого `Source`, выигрывает от двухслойного scope:

- `scope_text`
  - краткое человеческое описание области применения
- `scope_json`
  - машинно-читаемый envelope

Рекомендуемый shape:

```json
{
  "scope_kind": "global | project | conversation | entity | environment | document",
  "project_id": null,
  "conversation_id": null,
  "subject_entity_id": null,
  "environment": null,
  "modality": "explicit | inferred | summarized | imported",
  "audience": "runtime | memory_worker | user_model"
}
```

`scope_text` нужен для explainability.
`scope_json` нужен для retrieval и reconciliation.

### 2.2 `truth status` и `retrieval_state`

Это разные оси и не надо их смешивать.

`truth status` отвечает:
- актуально ли знание
- вытеснено ли
- опровергнуто ли
- закончилось ли его временное окно

`retrieval_state` отвечает:
- как часто item должен участвовать в поиске
- находится ли он в hot / warm / cold памяти
- архивирован ли он
- скрыт ли из обычного retrieval

Общий паттерн для retrieval lifecycle:
- `active`
- `warm`
- `cold`
- `archived`
- `deleted`

### 2.3 Usage fields

Для notes / claims / tasks / episodes полезны:
- `use_count`
- `last_used_at`
- `last_validated_at`

Это нужно для:
- maturity
- retention
- repair

## 3. Главные сущности

### 3.1 `Source`

Сырой источник знания.

Назначение:
- хранить chat turns, tool outputs, imported notes, external records
- быть первичным evidence layer
- служить опорой для re-check и explainability

Рекомендуемые поля:
- `source_id: uuid`
- `namespace_id: text`
- `source_type: chat_turn | tool_output | imported_note | external_record`
- `conversation_id: text | null`
- `turn_id: text | null`
- `speaker_role: user | assistant | tool | system | external`
- `author_label: text | null`
- `content_text: text`
- `content_hash: text`
- `observed_at: timestamptz`
- `created_at: timestamptz`
- `metadata_json: jsonb`
- `retention_class: standard | short_lived | sensitive | imported`
- `expires_at: timestamptz | null`
- `deleted_at: timestamptz | null`
- `embedding: vector | null`

Инварианты:
- source не должен silently изменяться
- `observed_at` хранится отдельно от ingestion time
- `content_hash` полезен для dedupe
- delete policy должна быть явной

---

### 3.2 `Entity`

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
- ошибочные merge должны быть обратимы через audit trail

---

### 3.3 `PredicateCatalog`

Это не memory item, а конфиг для reconciliation.

Почему нужен:
- у разных предикатов разная cardinality
- у разных предикатов разная temporal semantics
- одни факты заменяются, другие сосуществуют
- часть facts идет в profile projection

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

### 3.4 `Note`

Промежуточный semantic memory fragment.

Это:
- не source
- не final claim
- не chain-of-thought

`Note` нужен для вещей, которые:
- уже важны
- полезны для retrieval
- но еще не должны быть зацементированы как hard fact

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
- `scope_json: jsonb`
- `status: active | superseded | retracted | resolved | stale | candidate`
- `retrieval_state: active | warm | cold | archived | deleted`
- `maturity_state: fresh | stabilizing | mature | consolidated`
- `maturity_score: numeric(4,3)`
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
- `last_validated_at: timestamptz | null`
- `evidence_count: integer`
- `archived_at: timestamptz | null`

Дополнительные таблицы:

#### `note_entities`
- `note_id`
- `entity_id`
- `entity_role: primary | secondary | mentioned | owner | subject`

#### `note_sources`
- `note_id`
- `source_id`
- `support_type: direct | summarized | imported`
- `evidence_span`
- `evidence_quote`
- `support_confidence`

#### `note_links`
- `from_note_id`
- `to_note_id`
- `link_type: supports | contradicts | refines | related | supersedes | derived_from`
- `link_weight`

Инварианты `Note`:
- одна note = один semantic fragment
- note должна быть понятна без исходного чата
- note не дублирует source без added value
- `candidate_claims_json` — только hints
- note может жить долго без превращения в claim, если полезен для retrieval

Пример:

```json
{
  "note_type": "decision",
  "title": "Use Postgres as memory source of truth",
  "summary": "The current direction is to keep Postgres with pgvector as the main memory store because it keeps structured memory, retrieval metadata, and transactions together. Neo4j is deferred until traversal-heavy use cases become core.",
  "scope_text": "Agent memory layer for the current product iteration",
  "scope_json": {
    "scope_kind": "project",
    "project_id": "agent_memory",
    "modality": "explicit"
  },
  "status": "active",
  "retrieval_state": "active",
  "maturity_state": "stabilizing",
  "maturity_score": 0.62,
  "confidence": 0.88,
  "importance": 8
}
```

---

### 3.5 `Claim`

Атомарный факт или relation edge.

Это ключевая таблица для:
- точных factual answers
- predicate-based reconciliation
- explainable retrieval

Рекомендуемые поля:
- `claim_id: uuid`
- `namespace_id`
- `subject_entity_id: uuid`
- `predicate: text`
- `object_entity_id: uuid | null`
- `object_value_json: jsonb | null`
- `normalized_text: text`
- `context_text: text | null`
- `scope_json: jsonb`
- `qualifiers_json: jsonb`
- `confidence: numeric(4,3)`
- `importance: smallint`
- `status: active | superseded | retracted | expired | candidate`
- `retrieval_state: active | warm | cold | archived | deleted`
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
- `last_validated_at: timestamptz | null`
- `archived_at: timestamptz | null`

Отдельная таблица `claim_sources`:
- `claim_id`
- `source_id`
- `support_type: direct | inferred | imported | derived_from_note`
- `evidence_span`
- `evidence_quote`
- `support_confidence`

Инварианты:
- один claim = одно predicate application
- claim должен иметь смысл без исходного чата
- explicit statement > inference
- relative time должен быть нормализован
- если смысл еще слишком мягкий, лучше `Note`, чем плохой `Claim`

---

### 3.6 `Profile`

Компактная always-in-context проекция.

Это не source of truth, а projection layer поверх:
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

Рекомендуемая структура:

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
- explicit facts важнее inference
- спорные facts лучше оставлять в claim/note, а не тащить в profile
- profile должен быть пересобираем

---

### 3.7 `Task`

Operational memory про будущее действие.

Задача нужна только когда реально требуется future action.

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
- `scope_json: jsonb`
- `acceptance_criteria_json: jsonb`
- `blockers_json: jsonb`
- `related_entity_ids_json: jsonb`
- `origin_note_id: uuid | null`
- `created_from_run_id: uuid | null`
- `confidence: numeric(4,3)`
- `retrieval_state: active | warm | cold | archived | deleted`
- `created_at`
- `updated_at`
- `closed_at`
- `use_count`
- `last_used_at`
- `archived_at`

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

### 3.8 `Episode`

Опциональный тип памяти для переносимого опыта.

Нужен не в MVP, а позже — когда хочется хранить:
- successful tactics
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
- `retrieval_state`
- `created_at`
- `updated_at`
- `embedding`
- `use_count`
- `last_used_at`
- `archived_at`

Инварианты:
- не хранить raw chain-of-thought
- episode — это lesson, а не полный reasoning trace

---

### 3.9 `MemoryRun`

Audit trail и debug слой.

Нужен, чтобы видеть:
- какой prompt сработал
- на какой модели
- что именно было retrieved
- какой budget был выделен
- какие ops были предложены
- какие ops реально применились
- где pipeline сломался

Рекомендуемые поля:
- `run_id`
- `namespace_id`
- `run_type: route | retrieve_update | canonicalize | construct_notes | reconcile_notes | extract_claims | reconcile_claims | update_profile | update_tasks | consolidate_notes | repair_memory | apply_retention | read_plan | compose_answer | compact`
- `trigger_mode: hot_path | background | manual | slow_path`
- `source_ids_json`
- `retrieved_item_ids_json`
- `retrieval_budget_json`
- `prompt_name`
- `prompt_version`
- `model_name`
- `input_hash`
- `output_json`
- `applied_ops_json`
- `repair_actions_json`
- `latency_ms`
- `token_input`
- `token_output`
- `status: success | failed | partial`
- `error_text`
- `created_at`

Инварианты:
- run log не должен исчезать silently
- `input_hash` полезен для идемпотентности и retry safety

## 4. Почему `Note` не заменяет `Claim`

Потому что у них разная работа.

### `Claim`
Нужен для:
- точных factual answers
- reconciliation через predicate rules
- profile updates
- time-aware status answers

### `Note`
Нужен для:
- richer retrieval
- decision history
- rationale
- partial or evolving meaning
- later consolidation

Если хранить все как `Claim`, получится ложная точность.
Если хранить все как `Note`, получится слишком мягкая память.

## 5. Note maturity

`Note` нельзя оставлять без модели зрелости.

Рекомендуемый паттерн:
- `fresh`
- `stabilizing`
- `mature`
- `consolidated`

Что может повышать maturity:
- note использовался в retrieval
- note поддержан несколькими sources
- note пережил время без supersede
- note покрывает recurring rationale questions

Что может понижать / обнулять:
- contradiction
- supersede
- scope drift
- long-term disuse

## 6. Retention / forgetting

`v3` требует явной retention policy.

Минимум:
- low-use notes можно переводить в `cold`
- old resolved items — в `archived`
- sensitive source data — soft/hard delete по request
- historical claims могут оставаться explainable, но редко участвовать в top retrieval

Отдельно полезно хранить:
- `retention_class`
- `expires_at`
- `deleted_at`
- `archived_at`

## 7. Recommended field descriptions для schema / Pydantic

### Для `Note`
- `summary`: "Self-contained semantic fragment. Keep uncertainty explicit and avoid pretending a provisional direction is a hard fact."
- `scope_json`: "Machine-readable scope boundaries. Use this to prevent over-generalization across projects, environments, or conversations."
- `maturity_score`: "How stable and reusable the note appears for later consolidation or repeated retrieval."

### Для `Claim`
- `normalized_text`: "Stand-alone factual statement that can be shown without the original chat."
- `scope_json`: "Machine-readable scope and modality for conflict resolution and retrieval filtering."
- `retrieval_state`: "Search temperature, distinct from truth status."

### Для `Task`
- `acceptance_criteria_json`: "Observable conditions for task completion."
- `scope_json`: "What this task applies to and where it should not leak."

### Для `Profile`
- `soft_signals`: "Weakly inferred stable tendencies. Keep them low confidence or omit them."

## 8. Модель данных в одной строке

- `Source` = что было сказано / увидено
- `Entity` = кто/что это вообще
- `Note` = что уже важно и полезно, но еще не хочется цементировать как факт
- `Claim` = что мы считаем нормализованным фактом
- `Profile` = компактная проекция устойчивого контекста
- `Task` = что должно быть сделано
- `Episode` = чему система научилась
- `MemoryRun` = как именно память обновлялась
