# 03. Prompt Pack

Ниже — набор промптов, которые я бы отдал разработчику как стартовый baseline.

## 0. Общие правила для всех memory prompts

### Обязательно передавать в prompt
- `current_time_iso`
- `timezone`
- `namespace_id`
- `source_ids`
- `latest_turns`
- `predicate_catalog_excerpt`
- только нужный контекст, а не весь лог целиком

### Технические правила
- structured output / JSON schema enforcement
- низкая температура
- строгая валидация результата
- если JSON невалиден → retry с validator feedback
- every prompt versioned: `router_v1`, `claim_extract_v2`, etc.

### Общие семантические правила
- лучше `noop`, чем мусорная память
- explicit statement > inference
- newer explicit statement > older inferred statement
- relative time must become absolute time
- один memory item = одна переиспользуемая единица
- не сохранять hypotheticals как facts
- requests и facts — не одно и то же

---

## 1. Memory Router

### Когда вызывать
Сразу после сохранения `Source`.

### Цель
Решить, стоит ли инициировать memory write pipeline.

### Выход
```json
{
  "decision": "noop | write_now | write_later",
  "memory_types": ["profile", "claim", "task", "episode"],
  "salience": 0.0,
  "reason": "short explanation"
}
```

### Prompt

```text
You are MemoryRouter v1 for a long-term AI agent.

Your job is to decide whether the latest turns contain durable information worth storing in long-term memory.

Current time: {{current_time_iso}}
Timezone: {{timezone}}
Namespace: {{namespace_id}}

Latest turns:
{{latest_turns}}

Return JSON:
{
  "decision": "noop | write_now | write_later",
  "memory_types": ["profile", "claim", "task", "episode"],
  "salience": 0.0,
  "reason": "short explanation"
}

Decision policy:
- "noop" when the turns contain greetings, filler, small talk, repeated wording, or transient content with low future value.
- "write_now" when the turns contain explicit requests to remember something, important stable preferences, new durable facts, major state changes, or clear commitments/tasks.
- "write_later" when the content is useful but not urgent, and can be processed in background without affecting the user response.

Type policy:
- "profile" for stable identity, communication preferences, constraints, expertise, recurring user style preferences.
- "claim" for atomic facts or relations that may be queried later.
- "task" for open commitments, TODOs, deadlines, or follow-ups requiring future action.
- "episode" only for reusable lessons, notable failures, or successful tactics.

Hard rules:
- Prefer "noop" over memory pollution.
- Do not use "episode" for ordinary factual turns.
- A plain user question is not automatically a task.
- If the user explicitly asks the assistant to remember something, do not return "noop".
- Return valid JSON only.
```

### Комментарий
Этот prompt должен быть дешевым и быстрым.
Его задача — не идеально понять смысл, а дешево отфильтровать мусор.

---

## 2. Write-Time Retrieval Planner

### Когда вызывать
После router, перед extraction/reconciliation.

### Цель
Сформировать поисковый план для candidate memories, чтобы extraction шел не “в вакууме”.

### Выход
```json
{
  "need_retrieval": true,
  "entity_queries": ["..."],
  "text_queries": ["..."],
  "predicate_hints": ["..."],
  "time_filters": {
    "from_iso": null,
    "to_iso": null
  },
  "memory_types": ["profile", "claim", "task", "source"],
  "limits": {
    "claims": 20,
    "sources": 8,
    "tasks": 10
  }
}
```

### Prompt

```text
You are WriteTimeRetrievalPlanner v1.

Goal:
Create a retrieval plan for memory update. The plan must fetch only the memories and evidence most likely to help with deduplication, contradiction detection, temporal updates, and profile/task refresh.

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
  "time_filters": {
    "from_iso": null,
    "to_iso": null
  },
  "memory_types": ["profile", "claim", "task", "source"],
  "limits": {
    "claims": 20,
    "sources": 8,
    "tasks": 10
  }
}

Rules:
- Bias retrieval toward possible duplicates and contradictions, not broad recall.
- Include entity-focused queries when named people, projects, tools, or technologies are mentioned.
- Include predicate hints when the turns look like preference, identity, task, or state-change statements.
- Add time filters when the turns mention “now”, “before”, “used to”, “from now on”, “no longer”, deadlines, or relative dates.
- Include "profile" when communication style or stable preferences may be affected.
- Include "task" when future action or deadlines are involved.
- Keep the plan precise; fewer better queries are preferred.
- Return valid JSON only.
```

### Комментарий
Это не строго обязательный prompt, но он сильно помогает на reconciliation.

---

## 3. Entity Canonicalizer

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
      "entity_type": "person | organization | project | repo | file | technology | product | location | concept | document | conversation",
      "canonical_name": "text",
      "summary": "text"
    },
    "alias_text": "text-or-null",
    "confidence": 0.0,
    "reason": "short explanation"
  }
]

Rules:
- Link to an existing entity when the evidence is strong enough.
- Create a new entity only when no candidate is a safe match.
- Use "add_alias" when the mention is a clear alias of an existing entity.
- Use "noop" for vague references, pronouns, or non-reusable mentions.
- Do not merge distinct people or projects on weak evidence.
- Prefer precision over recall.
- Return valid JSON only.
```

### Комментарий
В entity resolution лучше быть осторожным.
Ложные merge обычно больнее, чем missed entity.

---

## 4. Claim Extractor

### Когда вызывать
После entity canonicalization.

### Цель
Извлечь атомарные candidate claims.

### Выход
```json
[
  {
    "subject_ref": "entity_id",
    "predicate": "snake_case_predicate",
    "object_ref": "entity_id-or-null",
    "object_value": null,
    "context_text": "small scope note",
    "qualifiers": {},
    "valid_from": null,
    "valid_to": null,
    "confidence": 0.91,
    "importance": 7,
    "normalized_text": "Standalone sentence."
  }
]
```

### Prompt

```text
You are ClaimExtractor v2.

Goal:
Extract atomic semantic memories from the latest turns as reusable claims.

Current time: {{current_time_iso}}
Timezone: {{timezone}}
Namespace: {{namespace_id}}

Predicate catalog:
{{predicate_catalog_excerpt}}

Resolved entities:
{{resolved_entities}}

Latest turns:
{{latest_turns}}

Return JSON array:
[
  {
    "subject_ref": "entity_id",
    "predicate": "snake_case_predicate",
    "object_ref": "entity_id-or-null",
    "object_value": null,
    "context_text": "small scope note",
    "qualifiers": {},
    "valid_from": null,
    "valid_to": null,
    "confidence": 0.0,
    "importance": 1,
    "normalized_text": "Standalone sentence."
  }
]

Extraction rules:
- One claim = one atomic fact or relation.
- Each claim must be understandable without the original chat.
- Use a predicate from the catalog whenever possible.
- Use "object_ref" for reusable entities; use "object_value" for literals.
- Convert all relative time expressions into absolute timestamps when possible.
- Include "valid_from" / "valid_to" when the truth is explicitly time-bounded.
- Put scope/modality/channel details into "qualifiers".
- Do not store greetings, rhetorical questions, hypotheticals, sarcasm, or weak guesses as claims.
- Do not create tasks here.
- Do not create profile-only prose here; emit claims that can later feed a profile projection.
- Prefer fewer high-precision claims over broad extraction.
- Return valid JSON only.
```

### Пример

#### Input
```text
Current time: 2026-04-16T10:00:00+03:00
Latest turns:
User: Мой родной язык русский. Сейчас работаю с питоном. Люблю котлин.
User: Отвечай мне по-русски, а комментарии в коде пиши по-английски.
```

#### Good output
```json
[
  {
    "subject_ref": "user_entity",
    "predicate": "speaks_language",
    "object_value": "ru",
    "object_ref": null,
    "context_text": "native language",
    "qualifiers": {
      "modality": "explicit"
    },
    "valid_from": null,
    "valid_to": null,
    "confidence": 0.99,
    "importance": 8,
    "normalized_text": "The user speaks Russian as a native language."
  },
  {
    "subject_ref": "user_entity",
    "predicate": "works_with",
    "object_ref": "entity_python",
    "object_value": null,
    "context_text": "current work stack",
    "qualifiers": {
      "modality": "explicit"
    },
    "valid_from": "2026-04-16T10:00:00+03:00",
    "valid_to": null,
    "confidence": 0.95,
    "importance": 7,
    "normalized_text": "The user is currently working with Python."
  },
  {
    "subject_ref": "user_entity",
    "predicate": "likes",
    "object_ref": "entity_kotlin",
    "object_value": null,
    "context_text": "technology preference",
    "qualifiers": {
      "modality": "explicit"
    },
    "valid_from": null,
    "valid_to": null,
    "confidence": 0.93,
    "importance": 6,
    "normalized_text": "The user likes Kotlin."
  },
  {
    "subject_ref": "user_entity",
    "predicate": "prefers_response_language",
    "object_ref": null,
    "object_value": "ru",
    "context_text": "assistant interaction preference",
    "qualifiers": {
      "modality": "explicit"
    },
    "valid_from": "2026-04-16T10:00:00+03:00",
    "valid_to": null,
    "confidence": 0.99,
    "importance": 9,
    "normalized_text": "The user prefers assistant responses in Russian."
  },
  {
    "subject_ref": "user_entity",
    "predicate": "prefers_code_comment_language",
    "object_ref": null,
    "object_value": "en",
    "context_text": "code style preference",
    "qualifiers": {
      "modality": "explicit"
    },
    "valid_from": "2026-04-16T10:00:00+03:00",
    "valid_to": null,
    "confidence": 0.99,
    "importance": 9,
    "normalized_text": "The user prefers code comments in English."
  }
]
```

### Комментарий
Claim extractor должен быть максимально скучным и дисциплинированным.
Его работа — не “понять все”, а выдать чистые атомарные candidate records.

---

## 5. Claim Reconciler

### Когда вызывать
После claim extraction и retrieval existing claims.

### Цель
Сопоставить новые claim candidates с существующими и решить, что реально писать в базу.

### Выход
```json
[
  {
    "action": "insert | supersede | retract | noop | merge_duplicate",
    "existing_claim_id": "uuid-or-null",
    "new_claim": { },
    "reason": "short explanation"
  }
]
```

### Prompt

```text
You are ClaimReconciler v2.

Goal:
Compare new candidate claims with existing memory and decide which operations should be applied.

Current time: {{current_time_iso}}
Timezone: {{timezone}}
Namespace: {{namespace_id}}

Predicate catalog:
{{predicate_catalog_excerpt}}

New candidate claims:
{{new_candidate_claims}}

Existing related claims:
{{existing_claims}}

Return JSON array:
[
  {
    "action": "insert | supersede | retract | noop | merge_duplicate",
    "existing_claim_id": "uuid-or-null",
    "new_claim": {},
    "reason": "short explanation"
  }
]

Reconciliation rules:
- Use "noop" when the new claim is already covered by an existing active claim.
- Use "merge_duplicate" when the new claim is semantically the same as an existing claim and only evidence should be extended.
- Use "supersede" when the new claim is a newer or stronger replacement for the same scope.
- Use "retract" only when the new evidence clearly invalidates an existing claim.
- Use "insert" when the claim is new and compatible with existing memory.
- Follow the predicate catalog:
  - single cardinality + replace => prefer one active claim per scope
  - multi cardinality + coexist => allow several active claims
  - time_scoped + range_split => preserve history when truth changes over time
- Explicit statements override weak inference.
- Newer direct evidence overrides older uncertain evidence.
- Keep both claims when they differ by time, project, environment, or scope.
- Never silently delete history; use supersede/retract semantics instead.
- Return valid JSON only.
```

### Пример

#### Existing memory
```json
[
  {
    "claim_id": "c1",
    "subject_ref": "user_entity",
    "predicate": "prefers_response_language",
    "object_value": "en",
    "status": "active",
    "valid_from": "2025-01-01T00:00:00Z",
    "valid_to": null,
    "confidence": 0.70
  }
]
```

#### New candidate
```json
[
  {
    "subject_ref": "user_entity",
    "predicate": "prefers_response_language",
    "object_value": "ru",
    "valid_from": "2026-04-16T10:00:00+03:00",
    "valid_to": null,
    "confidence": 0.99,
    "normalized_text": "The user prefers assistant responses in Russian."
  }
]
```

#### Good output
```json
[
  {
    "action": "supersede",
    "existing_claim_id": "c1",
    "new_claim": {
      "subject_ref": "user_entity",
      "predicate": "prefers_response_language",
      "object_value": "ru",
      "valid_from": "2026-04-16T10:00:00+03:00",
      "valid_to": null,
      "confidence": 0.99,
      "normalized_text": "The user prefers assistant responses in Russian."
    },
    "reason": "Same single-valued preference, newer explicit statement with higher confidence."
  }
]
```

### Комментарий
Это один из самых важных prompt'ов во всей системе.
Если он слабый, база быстро превращается в свалку.

---

## 6. Profile Updater

### Когда вызывать
После retrieval и/или после reconciliation.

### Цель
Обновить compact in-context profile.

### Выход
Полный `profile_json`.

### Prompt

```text
You are ProfileUpdater v2.

Goal:
Maintain exactly one concise structured profile for the target user or agent.

Current time: {{current_time_iso}}
Timezone: {{timezone}}
Namespace: {{namespace_id}}

Existing profile JSON:
{{existing_profile_json}}

Stable or relevant claims:
{{relevant_claims}}

Latest turns:
{{latest_turns}}

Return a full JSON object with this schema:
{
  "preferred_name": null,
  "languages": [],
  "timezone": null,
  "communication": {
    "response_language": null,
    "detail_default": "short | medium | long",
    "tone": null,
    "code_comment_language": null
  },
  "technical": {
    "primary_stack": [],
    "secondary_stack": [],
    "domains": []
  },
  "stable_preferences": [],
  "constraints": [],
  "current_focus": [],
  "soft_signals": []
}

Update rules:
- Keep the profile concise and stable.
- Prefer explicit user statements over inference.
- Preserve existing fields unless contradicted by stronger evidence.
- Store repeated or durable facts/preferences here, not one-off remarks.
- Put uncertain or weakly inferred signals into "soft_signals" or omit them.
- Use "constraints" for instructions that should shape assistant behavior.
- Use "current_focus" for active but non-permanent areas of work.
- Do not restate all claims; keep only the compact reusable profile.
- Return valid JSON only.
```

### Пример good behavior
- `prefers_response_language = ru` → `communication.response_language = "ru"`
- `prefers_code_comment_language = en` → `communication.code_comment_language = "en"`
- `works_with Python` + repeated recent mention → `technical.primary_stack += ["Python"]`
- `likes Kotlin` but not current primary tool → `technical.secondary_stack += ["Kotlin"]`

### Комментарий
Профиль должен быть всегда коротким.
Если он превратился в summary на полторы страницы — значит prompt или compaction сломались.

---

## 7. Task Updater

### Когда вызывать
После router/retrieval, если есть шанс, что появились commitments.

### Цель
Извлечь и обновить открытые задачи.

### Выход
```json
[
  {
    "action": "insert | update | close | noop",
    "existing_task_id": "uuid-or-null",
    "task": {
      "title": "text",
      "description": "text-or-null",
      "owner_ref": "entity_id-or-null",
      "assignee_ref": "entity_id-or-null",
      "status": "open | in_progress | blocked | done | cancelled",
      "priority": "low | normal | high",
      "due_at": null,
      "acceptance_criteria": [],
      "blockers": [],
      "related_entity_ids": [],
      "confidence": 0.0
    },
    "reason": "short explanation"
  }
]
```

### Prompt

```text
You are TaskUpdater v1.

Goal:
Extract and update actionable commitments and follow-ups from the latest turns.

Current time: {{current_time_iso}}
Timezone: {{timezone}}
Namespace: {{namespace_id}}

Latest turns:
{{latest_turns}}

Existing open tasks:
{{existing_open_tasks}}

Return JSON array:
[
  {
    "action": "insert | update | close | noop",
    "existing_task_id": "uuid-or-null",
    "task": {
      "title": "text",
      "description": "text-or-null",
      "owner_ref": "entity_id-or-null",
      "assignee_ref": "entity_id-or-null",
      "status": "open | in_progress | blocked | done | cancelled",
      "priority": "low | normal | high",
      "due_at": null,
      "acceptance_criteria": [],
      "blockers": [],
      "related_entity_ids": [],
      "confidence": 0.0
    },
    "reason": "short explanation"
  }
]

Rules:
- Create a task only if future action is required.
- A user request is not automatically an assistant commitment unless your product policy says so or the assistant explicitly accepted it.
- Update existing tasks when the same work item changes status, due date, or scope.
- Close tasks when they are clearly completed or cancelled.
- Convert relative deadlines to absolute timestamps.
- Prefer one clear task over several vague ones.
- Return valid JSON only.
```

### Комментарий
Если продукт подразумевает, что любая user request автоматически становится task для assistant, это стоит зафиксировать в system policy и в prompt.

---

## 8. Read-Time Retrieval Planner

### Когда вызывать
На read path, до memory search.

### Цель
Понять, какие memory types и filters нужны для ответа.

### Выход
```json
{
  "need_memory": true,
  "memory_types": ["profile", "claim", "task", "episode", "source"],
  "text_queries": ["..."],
  "predicate_hints": ["..."],
  "entity_filters": ["..."],
  "time_filters": {
    "from_iso": null,
    "to_iso": null
  },
  "result_limits": {
    "profile": 1,
    "claims": 12,
    "tasks": 8,
    "episodes": 4,
    "sources": 6
  },
  "abstain_if_memory_missing": true
}
```

### Prompt

```text
You are ReadTimeRetrievalPlanner v1.

Goal:
Create a precise memory retrieval plan for answering the current user request.

Current time: {{current_time_iso}}
Timezone: {{timezone}}
Namespace: {{namespace_id}}

Current user request:
{{current_user_request}}

Recent turns summary:
{{recent_turns_summary}}

Core profile:
{{core_profile}}

Return JSON:
{
  "need_memory": true,
  "memory_types": ["profile", "claim", "task", "episode", "source"],
  "text_queries": ["..."],
  "predicate_hints": ["..."],
  "entity_filters": ["..."],
  "time_filters": {
    "from_iso": null,
    "to_iso": null
  },
  "result_limits": {
    "profile": 1,
    "claims": 12,
    "tasks": 8,
    "episodes": 4,
    "sources": 6
  },
  "abstain_if_memory_missing": true
}

Rules:
- Set "need_memory" to false only when the request is purely general knowledge and does not depend on prior user-specific context or history.
- Use "profile" for identity, preferences, communication style, and stable context.
- Use "task" for commitments, TODOs, deadlines, and follow-ups.
- Use "claim" for factual or relational memory.
- Use "source" when exact wording, provenance, or recent raw evidence may matter.
- Add time filters when the request is temporal ("now", "before", "last week", "currently", "used to").
- Prefer precision over breadth.
- If the answer depends on memory and the likely evidence is absent, set "abstain_if_memory_missing" to true.
- Return valid JSON only.
```

---

## 9. Answer Composer

### Когда вызывать
После retrieval на read path.

### Цель
Собрать ответ, grounded в памяти и не перепутать уверенность с догадкой.

### Выход
Можно вернуть обычный text answer или structured answer object.

### Prompt

```text
You are AnswerComposer v1.

Goal:
Answer the user using retrieved memory items and only the necessary general reasoning.

Current time: {{current_time_iso}}
Timezone: {{timezone}}

User request:
{{current_user_request}}

Retrieved profile:
{{retrieved_profile}}

Retrieved claims:
{{retrieved_claims}}

Retrieved tasks:
{{retrieved_tasks}}

Retrieved episodes:
{{retrieved_episodes}}

Retrieved source snippets:
{{retrieved_sources}}

Instructions:
- Prioritize retrieved memory over generic assumptions.
- If memory items conflict, acknowledge the conflict and prefer newer stronger evidence.
- If the request depends on missing or weak memory, say that the memory is insufficient rather than inventing details.
- Keep provenance mentally grounded in the retrieved items.
- Do not expose internal ids unless the product UX wants them.
- If the user asks for a recommendation or action plan, combine memory evidence with reasoning, but distinguish remembered facts from suggestions.

Return the final answer text only.
```

### Комментарий
Этот prompt особенно важен для честного abstention.

---

## 10. Offline Compactor

### Когда вызывать
По cron или debounce, не в hot path.

### Цель
Чистить memory graph без потери evidence.

### Выход
```json
{
  "entity_ops": [],
  "claim_ops": [],
  "profile_refresh": false,
  "episode_ops": [],
  "notes": "short explanation"
}
```

### Prompt

```text
You are OfflineCompactor v1.

Goal:
Consolidate related memory items without losing evidence or recoverability.

Current time: {{current_time_iso}}
Namespace: {{namespace_id}}

Clustered related entities:
{{related_entities}}

Clustered related claims:
{{related_claims}}

Current profile:
{{current_profile}}

Return JSON:
{
  "entity_ops": [],
  "claim_ops": [],
  "profile_refresh": false,
  "episode_ops": [],
  "notes": "short explanation"
}

Rules:
- Merge exact or near-exact duplicates when safe.
- Prefer reversible operations.
- Do not delete sources.
- Keep temporal history when facts changed over time.
- Suggest profile refresh when the stable surface has drifted.
- Use episode ops only for reusable lessons, not raw transcripts.
- Return valid JSON only.
```

---

## 11. Optional: Hot-Path Memory Tool Instructions

Если user-facing агенту дать tools для памяти, я бы описал их так.

### `search_memory` tool description
```text
Search long-term memory when the answer may depend on user-specific history, preferences, projects, prior commitments, or previously discussed facts. Prefer search before making assumptions about the user's past, current goals, or working style.
```

### `write_memory` tool description
```text
Write to long-term memory only when:
1. The user explicitly asks to remember something.
2. You learn a durable user preference, constraint, or identity fact.
3. You need to record an important commitment or stable project context.
4. You detect that an existing memory is outdated or incorrect.

Do not write greetings, filler, temporary moods, or low-value chatter.
```

---

## 12. Prompt engineering notes

### Что реально помогает
- predicate catalog в prompt
- absolute time в prompt
- retrieval candidate memories в prompt
- явный `noop`
- явный `retract/supersede`
- примеры good output
- отдельные prompts вместо одного монстра

### Что часто ломает качество
- один prompt на все стадии сразу
- отсутствие existing memory в reconcile prompt
- отсутствие cardinality/conflict policy для predicates
- отсутствие time semantics
- слишком широкий контекст
- разрешение модели писать свободный prose вместо схемы

---

## 13. Suggested execution order

### Write path
1. `MemoryRouter`
2. `WriteTimeRetrievalPlanner`
3. retrieve candidate items
4. `EntityCanonicalizer`
5. `ClaimExtractor`
6. `ClaimReconciler`
7. `TaskUpdater`
8. `ProfileUpdater`
9. apply DB transaction
10. optionally `OfflineCompactor`

### Read path
1. `ReadTimeRetrievalPlanner`
2. retrieve memory
3. `AnswerComposer`

---

## 14. Minimum validation layer outside the model

Даже хорошие prompts не отменяют deterministic guards.
Я бы добавил такие проверки:

- schema validation
- predicate exists in catalog
- exactly one of `object_ref` / `object_value`
- `confidence` in range
- `valid_to >= valid_from`
- `normalized_text` non-empty
- active claim must have evidence
- duplicate hash check for same `(subject, predicate, object, scope, validity)`

---

## 15. One practical recommendation

Если выбирать, куда вкладываться первым делом, то не в router, а в:
- predicate catalog
- claim reconciler
- profile updater
- eval harness

Именно они обычно решают, будет memory usable или превратится в шум.
