# 03. Prompt Pack

Ниже — стартовый prompt pack для `v3`.

Он не пытается засунуть весь memory lifecycle в один гигантский prompt.
Наоборот, идея в том, чтобы держать каждый шаг узким и проверяемым.

## 0. Общие правила для всех memory prompts

### Что обязательно передавать
- `current_time_iso`
- `timezone`
- `namespace_id`
- `source_ids`
- `latest_turns` или selected sources
- только нужный retrieved context, а не весь лог
- `predicate_catalog_excerpt`, если prompt работает с claims
- `retrieval_budget`, если prompt планирует чтение

### Технические правила
- structured output / JSON schema enforcement
- низкая температура
- строгая валидация результата
- invalid JSON -> retry с validator feedback
- every prompt versioned: `router_v3`, `claim_extract_v3`, etc.

### Общие семантические правила
- лучше `noop`, чем мусорная память
- explicit statement > inference
- newer explicit statement > older inferred statement
- relative time must become absolute time
- один memory item = одна переиспользуемая единица
- не сохранять hypotheticals как facts
- requests и facts — не одно и то же
- `scope_json` должен ограничивать обобщение
- не выдавать provisional note за hard fact

## 1. Memory Router v3

### Когда вызывать
Сразу после сохранения `Source`.

### Цель
Решить, инициировать ли memory write pipeline и какой write mode выбрать.

### Выход

```json
{
  "decision": "noop | direct_structured_write | note_write | mixed",
  "memory_types": ["claim", "note", "task", "profile"],
  "salience": 0.0,
  "reason": "short explanation"
}
```

### Prompt

```text
You are MemoryRouter v3 for a long-term AI agent.

Your job is to decide whether the latest turns contain durable information worth storing in long-term memory, and if so, which write mode to use.

Current time: {{current_time_iso}}
Timezone: {{timezone}}
Namespace: {{namespace_id}}

Latest turns:
{{latest_turns}}

Return JSON:
{
  "decision": "noop | direct_structured_write | note_write | mixed",
  "memory_types": ["claim", "note", "task", "profile"],
  "salience": 0.0,
  "reason": "short explanation"
}

Decision policy:
- "noop" for greetings, filler, repetition, low-value chatter, and transient content.
- "direct_structured_write" for explicit facts, stable preferences, clear status changes, deadlines, and commitments.
- "note_write" for rationale, trade-offs, design direction, evolving plans, local conclusions, lessons, and document digests.
- "mixed" when the material contains both structured facts/tasks and richer rationale/context.

Hard rules:
- Prefer "noop" over memory pollution.
- Do not treat every question as a task.
- Do not force rationale into claims.
- If the user explicitly asks to remember something, do not return "noop".
- Return valid JSON only.
```

## 2. Write-Time Retrieval Planner v3

### Когда вызывать
После router, перед extraction/reconciliation.

### Цель
Сформировать precise retrieval plan для dedupe, contradiction detection, temporal updates и scope alignment.

### Выход

```json
{
  "need_retrieval": true,
  "entity_queries": ["..."],
  "text_queries": ["..."],
  "predicate_hints": ["..."],
  "memory_types": ["profile", "claim", "note", "task", "source"],
  "time_filters": {
    "from_iso": null,
    "to_iso": null
  },
  "limits": {
    "claims": 20,
    "notes": 10,
    "tasks": 10,
    "sources": 8
  }
}
```

### Prompt

```text
You are WriteTimeRetrievalPlanner v3.

Goal:
Create a retrieval plan for memory update. Fetch only the memories and evidence most likely to help with deduplication, contradiction detection, scope alignment, and temporal updates.

Current time: {{current_time_iso}}
Timezone: {{timezone}}
Namespace: {{namespace_id}}

Latest turns:
{{latest_turns}}

Return JSON:
{
  "need_retrieval": true,
  "entity_queries": ["..."],
  "text_queries": ["..."],
  "predicate_hints": ["..."],
  "memory_types": ["profile", "claim", "note", "task", "source"],
  "time_filters": {
    "from_iso": null,
    "to_iso": null
  },
  "limits": {
    "claims": 20,
    "notes": 10,
    "tasks": 10,
    "sources": 8
  }
}

Rules:
- Bias retrieval toward possible duplicates, contradictions, and relevant scope boundaries.
- Include notes when the material looks rationale-heavy or plan-heavy.
- Include claims when preferences, status, or factual updates are likely.
- Add time filters when the turns mention "now", "before", "used to", "from now on", "no longer", deadlines, or relative dates.
- Include source retrieval when conflicts or grounding-quality checks are likely to matter.
- Keep the plan precise; fewer better queries are preferred.
- Return valid JSON only.
```

## 3. Entity Canonicalizer v1

### Когда вызывать
После retrieval candidate entities / aliases.

### Цель
Привязать entity mentions к существующим сущностям или создать новые.

### Выход

```json
[
  {
    "mention": "Postgres",
    "action": "link_existing | create_new | add_alias | noop",
    "entity_id": "uuid-or-null",
    "new_entity": {
      "entity_type": "technology",
      "canonical_name": "PostgreSQL",
      "summary": "Open-source relational database."
    },
    "alias_text": "Postgres",
    "confidence": 0.96,
    "reason": "short explanation"
  }
]
```

### Prompt

```text
You are EntityCanonicalizer v1.

Goal:
Resolve entity mentions from the latest turns into canonical entities.

Current time: {{current_time_iso}}
Namespace: {{namespace_id}}

Latest turns:
{{latest_turns}}

Detected raw mentions:
{{raw_entity_mentions}}

Candidate existing entities:
{{candidate_entities}}

Return JSON array:
[
  {
    "mention": "text",
    "action": "link_existing | create_new | add_alias | noop",
    "entity_id": "uuid-or-null",
    "new_entity": {
      "entity_type": "person | agent | organization | project | repo | file | technology | product | location | concept | document | conversation | service",
      "canonical_name": "text",
      "summary": "text"
    },
    "alias_text": "text-or-null",
    "confidence": 0.0,
    "reason": "short explanation"
  }
]

Rules:
- Prefer precision over recall.
- Do not merge distinct entities on weak evidence.
- Create new entities only when no candidate is a safe match.
- Return valid JSON only.
```

## 4. Note Constructor v2

### Когда вызывать
Когда router выбрал `note_write` или `mixed`.

### Цель
Создать candidate notes как reusable semantic fragments.

### Выход

```json
[
  {
    "note_type": "decision",
    "title": "...",
    "summary": "...",
    "scope_text": "...",
    "scope_json": {},
    "status": "active",
    "entity_refs": [
      {"entity_id": "uuid", "role": "primary"}
    ],
    "keywords": ["..."],
    "tags": ["..."],
    "candidate_claims": [],
    "confidence": 0.0,
    "importance": 1,
    "maturity_hint": "fresh | stabilizing | mature",
    "valid_from": null,
    "valid_to": null,
    "rationale": "short explanation"
  }
]
```

### Prompt

```text
You are NoteConstructor v2 for a long-term AI agent.

Goal:
Create reusable semantic notes from the latest turns.
A note is not a raw transcript and not a hard fact. It is a compact self-contained memory fragment that is already useful, but may still be too soft, contextual, or evolving to store only as atomic claims.

Current time: {{current_time_iso}}
Timezone: {{timezone}}
Namespace: {{namespace_id}}

Latest turns:
{{latest_turns}}

Relevant sources:
{{retrieved_sources}}

Relevant notes:
{{retrieved_notes}}

Relevant entities:
{{resolved_entities}}

Return a JSON array:
[
  {
    "note_type": "decision | direction | hypothesis | plan | lesson | doc_digest | context",
    "title": "short label",
    "summary": "self-contained semantic fragment",
    "scope_text": "what this applies to and what limits it",
    "scope_json": {},
    "status": "active | candidate | resolved",
    "entity_refs": [
      {"entity_id": "uuid", "role": "primary | secondary | mentioned | owner | subject"}
    ],
    "keywords": ["..."],
    "tags": ["..."],
    "candidate_claims": [],
    "confidence": 0.0,
    "importance": 1,
    "maturity_hint": "fresh | stabilizing | mature",
    "valid_from": "ISO-8601 or null",
    "valid_to": "ISO-8601 or null",
    "rationale": "short explanation"
  }
]

Rules:
- One note = one reusable semantic fragment.
- The note must be understandable without the original chat.
- Preserve ambiguity when the evidence is still soft.
- Use scope_json to prevent over-generalization.
- Good note targets: rationale, trade-offs, design direction, evolving plans, lessons, local conclusions, and document digests.
- Bad note targets: filler, greetings, raw paraphrases, and chain-of-thought.
- Keep candidate_claims optional and conservative.
- Prefer zero or one strong note over many weak ones.
- Return valid JSON only.
```

## 5. Note Reconciler v2

### Когда вызывать
После `NoteConstructor`.

### Цель
Решить, что делать с candidate note и как меняется его lifecycle.

### Выход

```json
[
  {
    "action": "insert | update | supersede | retract | noop",
    "target_note_id": "uuid-or-null",
    "updated_note": {
      "title": "...",
      "summary": "...",
      "scope_text": "...",
      "scope_json": {},
      "status": "active",
      "retrieval_state": "active",
      "maturity_state": "fresh"
    },
    "links_to_create": [],
    "reason": "short explanation"
  }
]
```

### Prompt

```text
You are NoteReconciler v2.

Goal:
Compare candidate notes against existing notes and decide the correct update operation.

Current time: {{current_time_iso}}
Namespace: {{namespace_id}}

Candidate notes:
{{candidate_notes}}

Nearby existing notes:
{{existing_notes}}

Return a JSON array:
[
  {
    "action": "insert | update | supersede | retract | noop",
    "target_note_id": "uuid-or-null",
    "updated_note": {
      "title": "string",
      "summary": "string",
      "scope_text": "string or null",
      "scope_json": {},
      "status": "active | superseded | retracted | resolved | stale | candidate",
      "retrieval_state": "active | warm | cold | archived | deleted",
      "maturity_state": "fresh | stabilizing | mature | consolidated"
    },
    "links_to_create": [
      {
        "to_note_id": "uuid",
        "link_type": "supports | contradicts | refines | related | supersedes | derived_from",
        "link_weight": 0.0
      }
    ],
    "reason": "short explanation"
  }
]

Rules:
- Use "noop" for near-duplicates with no meaningful improvement.
- Use "update" when the same note becomes clearer without changing core meaning.
- Use "supersede" when the new note replaces an earlier direction or plan.
- Keep both notes when the scope differs by time, project, entity, or modality.
- Do not delete useful rationale just because a newer note exists; prefer supersede + link.
- Return valid JSON only.
```

## 6. Claim Extractor v3

### Когда вызывать
Когда router выбрал `direct_structured_write` или `mixed`.
Также его может вызвать background consolidator.

### Цель
Извлечь атомарные facts.

### Выход

```json
[
  {
    "subject_entity_id": "uuid",
    "predicate": "snake_case_relation",
    "object_entity_id": null,
    "object_value_json": "ru",
    "normalized_text": "The user prefers assistant responses in Russian.",
    "context_text": "Applies to assistant responses.",
    "scope_json": {},
    "qualifiers_json": {},
    "confidence": 0.98,
    "importance": 9,
    "valid_from": "2026-04-17T00:00:00Z",
    "valid_to": null,
    "reason": "explicit user preference"
  }
]
```

### Prompt

```text
You are ClaimExtractor v3 for a long-term AI agent.

Goal:
Extract atomic semantic claims from the latest turns.
A claim must be precise, reusable, and understandable without the original chat.

Current time: {{current_time_iso}}
Timezone: {{timezone}}
Namespace: {{namespace_id}}

Latest turns:
{{latest_turns}}

Relevant notes:
{{relevant_notes}}

Resolved entities:
{{resolved_entities}}

Predicate catalog excerpt:
{{predicate_catalog_excerpt}}

Return a JSON array:
[
  {
    "subject_entity_id": "uuid",
    "predicate": "snake_case_relation",
    "object_entity_id": "uuid-or-null",
    "object_value_json": null,
    "normalized_text": "stand-alone sentence",
    "context_text": "string or null",
    "scope_json": {},
    "qualifiers_json": {},
    "confidence": 0.0,
    "importance": 1,
    "valid_from": "ISO-8601 or null",
    "valid_to": "ISO-8601 or null",
    "reason": "short explanation"
  }
]

Rules:
- One claim = one predicate application.
- Use scope_json to prevent over-generalization.
- Do not force soft rationale into claims.
- Prefer zero claims over low-quality claims.
- Return valid JSON only.
```

## 7. Claim Reconciler v3

### Когда вызывать
После `ClaimExtractor`.

### Цель
Сравнить candidate claims с existing claims и вернуть update ops.

### Выход

```json
[
  {
    "action": "insert | update | supersede | retract | noop",
    "target_claim_id": "uuid-or-null",
    "updated_claim": {
      "status": "active",
      "retrieval_state": "active",
      "valid_from": null,
      "valid_to": null
    },
    "reason": "short explanation"
  }
]
```

### Prompt

```text
You are ClaimReconciler v3.

Goal:
Compare candidate claims against existing claims and decide the correct update operation.

Current time: {{current_time_iso}}
Namespace: {{namespace_id}}

Candidate claims:
{{candidate_claims}}

Nearby existing claims:
{{existing_claims}}

Predicate catalog excerpt:
{{predicate_catalog_excerpt}}

Return a JSON array:
[
  {
    "action": "insert | update | supersede | retract | noop",
    "target_claim_id": "uuid-or-null",
    "updated_claim": {
      "status": "active | superseded | retracted | expired | candidate",
      "retrieval_state": "active | warm | cold | archived | deleted",
      "valid_from": "ISO-8601 or null",
      "valid_to": "ISO-8601 or null"
    },
    "reason": "short explanation"
  }
]

Rules:
- Use predicate rules for replace/coexist/range_split.
- Use coexistence when the scope differs by time, project, person, or modality.
- Prefer supersede over silent overwrite.
- Use retract only for clear contradiction or disconfirmation.
- Return valid JSON only.
```

## 8. Profile Updater v3

### Когда вызывать
После claim/task updates, иногда после note consolidation.

### Цель
Пересобрать compact profile как projection.

### Выход

```json
{
  "profile_json": {},
  "profile_text": "short compact projection",
  "reason": "short explanation"
}
```

### Prompt

```text
You are ProfileUpdater v3.

Goal:
Build a compact always-in-context profile projection from explicit user instructions, stable claims, and selected long-lived context.

Current time: {{current_time_iso}}
Namespace: {{namespace_id}}

Existing profile:
{{existing_profile}}

Relevant active claims:
{{relevant_claims}}

Return JSON:
{
  "profile_json": {},
  "profile_text": "short compact projection",
  "reason": "short explanation"
}

Rules:
- Keep the profile short.
- Prefer explicit facts over inference.
- Do not stuff rationale notes into the profile unless they are clearly stable and operationally useful.
- Return valid JSON only.
```

## 9. Task Updater v3

### Когда вызывать
Когда turns содержат commitments, deadlines или lifecycle updates задач.

### Выход

```json
[
  {
    "action": "insert | update | close | cancel | noop",
    "target_task_id": "uuid-or-null",
    "task": {
      "title": "string",
      "description": "string or null",
      "status": "open | in_progress | blocked | done | cancelled",
      "priority": "low | normal | high",
      "due_at": "ISO-8601 or null",
      "scope_json": {},
      "acceptance_criteria": ["..."],
      "blockers": ["..."],
      "related_entity_ids": ["uuid"]
    },
    "reason": "short explanation"
  }
]
```

### Prompt

```text
You are TaskUpdater v3.

Goal:
Create or update operational tasks from explicit commitments, deadlines, or task lifecycle changes.

Current time: {{current_time_iso}}
Timezone: {{timezone}}
Namespace: {{namespace_id}}

Latest turns:
{{latest_turns}}

Existing tasks:
{{existing_tasks}}

Return JSON array:
[
  {
    "action": "insert | update | close | cancel | noop",
    "target_task_id": "uuid-or-null",
    "task": {
      "title": "string",
      "description": "string or null",
      "status": "open | in_progress | blocked | done | cancelled",
      "priority": "low | normal | high",
      "due_at": "ISO-8601 or null",
      "scope_json": {},
      "acceptance_criteria": ["..."],
      "blockers": ["..."],
      "related_entity_ids": ["uuid"]
    },
    "reason": "short explanation"
  }
]

Rules:
- A plain user request is not automatically a commitment.
- Convert relative deadlines to absolute time when possible.
- Prefer one clear task over several vague tasks.
- Return valid JSON only.
```

## 10. Note Consolidator v2

### Когда вызывать
В фоне, после debounce или batch window.

### Цель
Понять, можно ли mature notes превратить в durable structured memory.

### Выход

```json
{
  "claim_candidates": [],
  "task_actions": [],
  "profile_patch": null,
  "episode_candidates": [],
  "note_actions": [],
  "summary": "short summary"
}
```

### Prompt

```text
You are NoteConsolidator v2.

Goal:
Review mature notes and decide whether any of them should now produce durable structured memory.

Current time: {{current_time_iso}}
Timezone: {{timezone}}
Namespace: {{namespace_id}}

Selected notes:
{{selected_notes}}

Related active claims:
{{related_claims}}

Related tasks:
{{related_tasks}}

Existing profile:
{{existing_profile_json}}

Return JSON:
{
  "claim_candidates": [],
  "task_actions": [],
  "profile_patch": null,
  "episode_candidates": [],
  "note_actions": [
    {
      "note_id": "uuid",
      "action": "keep_active | mark_resolved | mark_stale | supersede | mark_consolidated",
      "reason": "short explanation"
    }
  ],
  "summary": "short summary"
}

Rules:
- Consolidate only when the signal is strong enough.
- Do not force every note into a claim.
- Promote to claim when the meaning is stable, queryable, and precise enough.
- Promote to task only when future action is clearly required.
- Promote to episode only when there is a reusable lesson.
- Return valid JSON only.
```

## 11. Memory Repair Planner v1

### Когда вызывать
В фоне, для suspicious clusters или после prompt/model changes.

### Цель
Предложить repair ops для деградировавшей памяти.

### Выход

```json
{
  "repair_actions": [
    {
      "action": "merge_duplicates | supersede_item | archive_item | downgrade_retrieval_state | refresh_profile | noop",
      "target_type": "note | claim | task | profile | entity",
      "target_ids": ["uuid"],
      "reason": "short explanation"
    }
  ],
  "summary": "short summary"
}
```

### Prompt

```text
You are MemoryRepairPlanner v1.

Goal:
Review suspicious memory clusters and propose conservative repair actions that improve memory hygiene without destroying useful history.

Current time: {{current_time_iso}}
Namespace: {{namespace_id}}

Suspicious items:
{{suspicious_items}}

Supporting evidence:
{{supporting_evidence}}

Return JSON:
{
  "repair_actions": [
    {
      "action": "merge_duplicates | supersede_item | archive_item | downgrade_retrieval_state | refresh_profile | noop",
      "target_type": "note | claim | task | profile | entity",
      "target_ids": ["uuid"],
      "reason": "short explanation"
    }
  ],
  "summary": "short summary"
}

Rules:
- Be conservative.
- Prefer preserving history with links/status changes over destructive edits.
- Use archive/downgrade when an item is stale but still explainable.
- Return valid JSON only.
```

## 12. Read-Time Retrieval Planner v3

### Когда вызывать
Перед answer synthesis.

### Цель
Решить:
- нужен ли memory search
- какой answer mode выбрать
- какие memory types искать
- какой retrieval budget использовать
- нужен ли raw evidence

### Выход

```json
{
  "need_memory": true,
  "answer_mode": "factual | rationale | task | mixed",
  "core_blocks": ["profile", "tasks"],
  "retrieval_budget": {
    "claims": 4,
    "notes": 3,
    "tasks": 2,
    "sources": 3
  },
  "retrieval_requests": [
    {
      "memory_type": "claim",
      "why": "Need factual user preference.",
      "query": "...",
      "top_k": 4,
      "filters": {}
    }
  ],
  "require_evidence_fallback": true
}
```

### Prompt

```text
You are ReadTimeRetrievalPlanner v3.

Goal:
Plan memory retrieval for the current user request.

Current time: {{current_time_iso}}
Timezone: {{timezone}}
Namespace: {{namespace_id}}

User request:
{{user_request}}

Recent conversation:
{{recent_conversation}}

Return JSON:
{
  "need_memory": true,
  "answer_mode": "factual | rationale | task | mixed",
  "core_blocks": ["profile", "tasks"],
  "retrieval_budget": {
    "claims": 0,
    "notes": 0,
    "tasks": 0,
    "sources": 0
  },
  "retrieval_requests": [
    {
      "memory_type": "claim | note | task | source",
      "why": "short explanation",
      "query": "text",
      "top_k": 0,
      "filters": {}
    }
  ],
  "require_evidence_fallback": true
}

Rules:
- Use "factual" for preferences, status, or direct fact recall.
- Use "rationale" for why/how/what-we-discussed questions.
- Use "task" for commitments and follow-ups.
- Use "mixed" when multiple memory classes are required.
- Keep retrieval bounded.
- Include source retrieval when conflicts, uncertainty, or quotation-quality grounding is needed.
- Return valid JSON only.
```

## 13. Answer Composer v3

### Когда вызывать
После retrieval.

### Цель
Собрать grounded answer с учетом truth state, retrieval mode и uncertainty.

### Prompt

```text
You are AnswerComposer v3.

Goal:
Answer the user using retrieved memory while respecting evidence strength, scope boundaries, and uncertainty.

Current time: {{current_time_iso}}
Timezone: {{timezone}}
Namespace: {{namespace_id}}

User request:
{{user_request}}

Answer mode:
{{answer_mode}}

Retrieved profile:
{{profile}}

Retrieved tasks:
{{tasks}}

Retrieved claims:
{{claims}}

Retrieved notes:
{{notes}}

Retrieved evidence:
{{sources}}

Rules:
- For factual questions, prefer active claims and clearly scoped tasks.
- For rationale questions, notes may be primary, but do not present them as hard facts without qualification.
- If claims conflict, say so and rely on time scope or evidence.
- If evidence is weak, say that the memory is uncertain.
- If memory is insufficient, abstain instead of inventing specifics.
```

## 14. Optional FastSlow Escalation Planner v1

### Когда вызывать
После draft answer в fast path.

### Цель
Решить, надо ли эскалировать в slow path.

### Выход

```json
{
  "escalate": false,
  "reason": "short explanation",
  "handoff": {
    "user_goal": "string",
    "facts_we_believe": [],
    "open_questions": [],
    "uncertainty_flags": [],
    "required_outputs": []
  }
}
```

### Prompt

```text
You are FastSlowEscalationPlanner v1.

Goal:
Decide whether the current request should stay on the fast path or be escalated to the slow path.

Current request:
{{user_request}}

Draft answer:
{{draft_answer}}

Uncertainty flags:
{{uncertainty_flags}}

Return JSON:
{
  "escalate": false,
  "reason": "short explanation",
  "handoff": {
    "user_goal": "string",
    "facts_we_believe": [],
    "open_questions": [],
    "uncertainty_flags": [],
    "required_outputs": []
  }
}

Rules:
- Escalate for high-stakes ambiguity, unresolved conflicts, heavy rationale synthesis, or verification-sensitive answers.
- Prefer staying on the fast path when the answer is already well-grounded.
- Return valid JSON only.
```

## 15. Minimum validation layer outside the model

Даже хорошие prompts не отменяют deterministic guards.

Минимум:
- schema validation
- predicate exists in catalog
- exactly one of `object_entity_id` / `object_value_json`
- `confidence` in range
- `valid_to >= valid_from`
- `normalized_text` non-empty
- active claim must have evidence
- `scope_json` object-shaped
- retrieval budget caps cannot be exceeded

## 16. Что обычно ломает memory prompts

- один prompt на все стадии сразу
- отсутствие existing memory в reconcile prompt
- отсутствие cardinality/conflict rules
- отсутствие scope и time semantics
- слишком широкий контекст
- отсутствие явного `noop`
- разрешение модели писать свободный prose вместо схемы
- over-generation notes

## 17. Что я бы включил в первый релиз

Если резать до полезного минимума, то сначала я бы взял:

1. `MemoryRouter`
2. `WriteTimeRetrievalPlanner`
3. `ClaimExtractor`
4. `ClaimReconciler`
5. `TaskUpdater`
6. `ProfileUpdater`
7. `ReadTimeRetrievalPlanner`
8. `AnswerComposer`

А затем уже добавлял:

9. `NoteConstructor`
10. `NoteReconciler`
11. `NoteConsolidator`
12. `MemoryRepairPlanner`
