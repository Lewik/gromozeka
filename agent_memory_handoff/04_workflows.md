# 04. Workflows

## 1. Общая идея

Память лучше обновлять не монолитным “суперпроцессом”, а специализированными шагами.

В `v3` есть:

- три write path:
  - `direct_structured_write`
  - `note_write`
  - `mixed`
- три background path:
  - `note_consolidation`
  - `memory_repair`
  - `retention_apply`

## 2. Write workflow — верхний уровень

```text
persist_source
  -> memory_router
      -> noop
      -> direct_structured_write
      -> note_write
      -> mixed

if not noop:
  -> write_time_retrieval
  -> entity_canonicalization
  -> direct structured ops and/or note ops
  -> transactional apply
  -> schedule consolidation / repair / retention
```

## 3. Direct structured write

### Когда использовать
- explicit preference
- explicit fact
- obvious status change
- task / deadline / commitment

### Pipeline

```text
source
  -> router
  -> retrieval planner
  -> retrieve nearby claims / tasks / profile / sources
  -> canonicalize entities
  -> claim extractor
  -> claim reconciler
  -> task updater
  -> profile updater
  -> apply transaction
```

### Что важно
- не делать claim extraction без retrieval
- не создавать task только потому, что пользователь о чем-то спросил
- profile обновлять как projection
- respect same-subject / same-predicate retrieval before update

## 4. Note write

### Когда использовать
- design discussions
- rationale / trade-offs
- evolving plans
- local conclusions
- lessons / patterns
- doc digests

### Pipeline

```text
source
  -> router
  -> retrieval planner
  -> retrieve nearby notes / claims / sources
  -> canonicalize entities
  -> note constructor
  -> note reconciler
  -> apply transaction
```

### Что важно
- `Note` должен добавлять смысл, а не пересказывать source
- note не обязан сразу конвертироваться в claim
- note должен быть self-contained
- у note должен быть явный `scope_json`

## 5. Mixed write

Это обычный кейс для knowledge-work чатов.

Пример:
- task: “собери архив для разработчика”
- note: “берем Postgres + pgvector как default, Neo4j позже”
- claim: “пользователь предпочитает ответы на русском”

### Pipeline

```text
source
  -> router(mixed)
  -> retrieval planner
  -> entity canonicalizer
  -> claim extractor + claim reconciler
  -> note constructor + note reconciler
  -> task updater
  -> profile updater
  -> apply transaction
```

### Что важно
`mixed` не означает “всё сохраняем дважды”.

Обычно одна и та же реплика дает:
- `0–2 claims`
- `0–1 task`
- `0–1 strong note`

## 6. Background note consolidation

Это ключевой путь, который превращает мягкую память в durable structured memory, когда сигнал созрел.

### Когда запускать
- после паузы в чате
- после окончания документа / обсуждения
- по cron для mature notes

### Pipeline

```text
select mature notes
  -> retrieve related claims / tasks / profile / notes
  -> note consolidator
  -> claim reconciliation
  -> task updates
  -> profile update
  -> optional episode synthesis
  -> mark notes keep_active / resolved / stale / superseded / consolidated
```

### Что считается mature note
Обычно:
- note использовался в retrieval
- note подтвержден несколькими sources
- note достаточно старый
- note стабилен и не конфликтует с новыми данными

### Что важно
- не принуждать каждую note стать claim
- unresolved rationale может оставаться note indefinitely
- consolidation должен повышать signal density, а не штамповать псевдо-facts

## 7. Memory repair workflow

Это отдельный background workflow.

### Когда запускать
- по cron
- после prompt/model change
- после всплеска contradictions / duplicates
- после import batch

### Pipeline

```text
detect suspicious clusters
  -> retrieve related notes / claims / profile / tasks / sources
  -> repair planner
  -> propose repair ops
  -> validate ops
  -> apply transaction
  -> log memory_run
```

### Что чинить
- duplicate notes
- contradictory active claims
- stale task states
- profile drift
- note spam
- bad entity merges
- scope leakage

### Что важно
- repair не должен silently переписывать history
- evidence-backed state важнее красивых summary
- repair должен быть audit-friendly

## 8. Retention / forgetting workflow

Retention — это не побочная уборка, а policy.

### Когда запускать
- по age thresholds
- по low use_count
- по namespace policy
- по explicit forget/delete request
- после archive compaction

### Pipeline

```text
select retention candidates
  -> classify keep / warm / cold / archive / delete
  -> apply retrieval_state changes
  -> archive or delete where policy allows
  -> log memory_run
```

### Что важно
- truth status и retrieval state не смешивать
- архивирование не равно опровержению
- delete policy должна учитывать lineage и legal/privacy constraints

## 9. Read workflow

### Step 1. Need memory?
Если вопрос:
- user-specific
- history-aware
- task-aware
- rationale-heavy
- temporal
- high-stakes

то retrieval нужен.

### Step 2. Load core blocks
Подтягиваем:
- profile
- active tasks
- optional session summary

### Step 3. Retrieval planning
Planner выбирает answer mode:
- `factual`
- `rationale`
- `task`
- `mixed`

### Step 4. Retrieval policy

#### `factual`
Искать в первую очередь:
- `claims`
- `tasks`
- `profile`

Budget:
- up to `6 claims`
- up to `2 tasks`
- up to `2 evidence snippets`

#### `rationale`
Искать в первую очередь:
- `notes`
- затем `claims`
- затем `sources`

Budget:
- up to `4 notes`
- up to `2 claims`
- up to `4 evidence snippets`

#### `task`
Искать:
- active/blocked tasks
- supporting notes if needed
- sources if task status is uncertain

Budget:
- up to `4 tasks`
- up to `2 notes`
- up to `2 evidence snippets`

#### `mixed`
Комбинировать:
- claims + notes + tasks
- evidence fallback when conflict exists

Budget:
- up to `4 claims`
- up to `3 notes`
- up to `2 tasks`
- up to `3 evidence snippets`

### Step 5. Compose answer
Composer должен:
- не выдавать note за hard fact без оговорки
- уважать scope boundaries
- честно говорить про conflict / uncertainty
- ходить до source, если нужна проверка

## 10. Идемпотентность и retry safety

### Нужно сразу предусмотреть
- dedupe на уровне source через `content_hash`
- `memory_runs.input_hash`
- транзакционное применение ops
- separation between proposed ops and applied ops

### Хороший паттерн
1. LLM предлагает ops
2. application layer валидирует ops
3. DB layer применяет ops транзакционно
4. `memory_runs` пишет фактически примененный результат

## 11. Error handling

### Что может ломаться
- invalid JSON
- missing entity ids
- claim/object mismatch
- too many low-quality notes
- accidental task explosion
- contradiction storms after model/prompt change
- retrieval budget overflow

### Что делать
- strict schema validation
- retry with validation feedback
- cap on number of outputs per step
- shadow mode before rollout
- metrics on duplicate / contradiction / note spam / budget overflow

## 12. Optional fast/slow runtime workflow

Это не обязательно для MVP, но полезно дальше.

### Fast path
- user-facing
- lower latency
- simple retrieval
- cheap draft
- escalation gate

### Slow path
- stronger model
- deeper reasoning
- answer verification
- note consolidation
- memory repair

### Pipeline

```text
user request
  -> fast runtime
  -> quick retrieval
  -> draft answer
  -> escalation planner
      -> no: answer user
      -> yes:
          -> typed handoff to slow path
          -> slow reasoning / verification / memory patch
```

### Typed handoff пример

```json
{
  "user_goal": "Understand which storage architecture to recommend.",
  "facts_we_believe": [
    "The user prefers concise answers in Russian.",
    "The current design uses Postgres + pgvector as baseline."
  ],
  "open_questions": [
    "Is the storage direction final or still provisional?"
  ],
  "uncertainty_flags": [
    "Need note-first retrieval for rationale."
  ],
  "required_outputs": [
    "final answer",
    "optional memory patch"
  ],
  "latency_tolerance": "medium"
}
```

### Что важно
Не гоняй между fast/slow свободный бесконечный текст.
Делай handoff как typed artifact.

## 13. Что я бы реально внедрял поэтапно

### Step 1
- sources
- claims
- profiles
- tasks
- read retrieval
- write reconciliation
- memory_runs

### Step 2
- notes
- note retrieval
- rationale answers
- retrieval budgets

### Step 3
- note consolidation
- memory repair
- retention policy
- episodes
