# 04. Workflows

## 1. Общая идея

Память лучше обновлять не монолитным “суперпроцессом”, а маленькими специализированными шагами.
В новой схеме появились три write path:

- `direct_structured_write`
- `note_write`
- `mixed`

Плюс один background path:
- `note_consolidation`

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
  -> schedule background consolidation
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
- profile обновлять как projection, а не как независимый source of truth

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
- `Note` должен добавлять смысл, а не просто пересказывать source
- note не обязан сразу конвертироваться в claim
- note может жить как отдельный retrieval-ready fragment

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
- 0–2 claims
- 0–1 task
- 0–1 strong note

## 6. Background note consolidation

Это ключевая новая часть.

### Когда запускать
После debounce или по batch window:
- после паузы в чате
- после окончания документа / обсуждения
- по cron для stale active notes

### Pipeline
```text
select mature notes
  -> retrieve related claims / tasks / profile / notes
  -> note consolidator
  -> claim reconciliation
  -> task updates
  -> profile update
  -> optional episode synthesis
  -> mark notes keep_active / resolved / stale / superseded
```

### Что считается mature note
Обычно:
- note уже несколько раз использовался
- note достаточно старый
- note подтвержден несколькими sources
- note достиг понятной стабильности

### Что важно
- не принуждать каждую note стать claim
- unresolved rationale может оставаться note indefinitely
- notes удобны даже без consolidation, если они хорошо отвечают на “почему / к чему склонялись / что обсуждали”

## 7. Read workflow

### Step 1. Need memory?
Если вопрос:
- user-specific
- history-aware
- task-aware
- rationale-heavy
- temporal

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

#### `rationale`
Искать в первую очередь:
- `notes`
- затем `claims`
- затем `sources` для grounding

#### `task`
Искать:
- active/blocked tasks
- supporting notes if needed
- sources if task status is uncertain

#### `mixed`
Комбинировать:
- claims + notes + tasks
- evidence fallback when conflict exists

### Step 5. Compose answer
Composer должен:
- не выдавать note за hard fact без оговорки
- честно говорить про conflict/uncertainty
- ходить до source, если нужна проверка

## 8. Пример маршрутизации

### Кейс A
Пользователь пишет: “Отвечай мне на русском, а комментарии в коде на английском.”

Результат:
- claim: `prefers_response_language = ru`
- claim: `prefers_code_comment_language = en`
- profile update

### Кейс B
Пользователь пишет: “Пока склоняюсь к Postgres как source of truth, Neo4j оставим на потом.”

Результат:
- note: storage direction / rationale
- возможно claim позже, после consolidation
- без спешки делать hard fact “final architecture”

### Кейс C
Пользователь пишет: “Собери новый архив для разработчика.”

Результат:
- task: open
- note: optional, если вокруг была важная rationale
- claims: обычно нет

## 9. Идемпотентность и retry safety

### Нужно сразу предусмотреть
- dedupe на уровне source через `content_hash`
- `memory_runs.input_hash`
- транзакционное применение ops
- безопасный retry при невалидном structured output
- separation between “LLM proposed ops” and “DB applied ops”

### Хороший паттерн
1. LLM предлагает ops
2. application layer валидирует ops
3. DB layer применяет ops транзакционно
4. `memory_runs` пишет фактически примененный результат

## 10. Error handling

### Что может ломаться
- invalid JSON
- missing entity ids
- claim/object mismatch
- too many low-quality notes
- accidental task explosion
- contradiction storms after model/prompt change

### Что делать
- strict schema validation
- retry with validation feedback
- cap on number of outputs per step
- metrics on duplicate rate / contradiction rate / note spam
- shadow mode before full rollout

## 11. Optional fast/slow runtime workflow

Это не обязательно для MVP, но полезно дальше.

### Fast path
- user-facing
- lower latency
- simple retrieval
- draft answer
- escalation gate

### Slow path
- stronger model
- deeper reasoning
- conflict resolution
- answer verification
- note consolidation / memory repair

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
          -> slow reasoning / verification
          -> refined answer and/or memory patch
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

## 12. Что я бы реально внедрял поэтапно

### Step 1
- sources
- claims
- profiles
- tasks
- read retrieval
- write reconciliation

### Step 2
- notes
- note retrieval
- note reconciler
- rationale answers

### Step 3
- background note consolidation
- episodes
- fast/slow escalation path
