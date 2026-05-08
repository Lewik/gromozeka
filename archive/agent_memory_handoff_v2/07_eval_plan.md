# 07. Evaluation Plan

## 1. Зачем нужен отдельный eval

У memory layer почти всегда есть ложное чувство “ну вроде работает”.
На практике деградация приходит тихо:

- claim precision падает
- stale claims растут
- note layer превращается в summary-spam
- task extraction начинает плодить мусор
- rationale answers звучат уверенно, но слабо grounded

Поэтому memory систему надо мерить отдельно от “качества ответа в среднем”.

## 2. Что именно мерить

### 2.1 Structured write quality
- claim precision
- claim recall на явных facts
- task precision / recall
- profile projection accuracy
- note precision
- note usefulness for retrieval
- duplicate rate
- contradiction rate
- stale-memory rate

### 2.2 Update quality
- supersede correctness
- retract correctness
- temporal normalization correctness
- correct coexist vs replace decisions
- note -> claim consolidation accuracy
- note -> task consolidation accuracy

### 2.3 Read quality
- factual answer grounding
- rationale answer grounding
- correct note-first vs claim-first retrieval choice
- abstention when evidence is weak
- conflict handling quality

### 2.4 Systems metrics
- write latency
- read latency
- token cost
- background job cost
- retrieval hit rate
- percentage of `noop`
- note generation rate per 100 turns

## 3. Отдельно про `Note` layer

`Note` надо проверять отдельно, иначе легко получить красивый, но бесполезный слой.

### Note-specific metrics
- **note precision** — действительно ли note содержит переиспользуемый semantic fragment
- **note novelty rate** — не дублирует ли note уже существующую note/source
- **note spam rate** — сколько notes не пригодились ни разу
- **note retrieval usefulness** — помогла ли note ответить на rationale/history question
- **consolidation yield** — сколько useful claims/tasks/episodes реально получилось из notes

### Красные флаги
- notes слишком похожи на summary исходных turns
- notes слишком длинные и не self-contained
- notes слишком много
- notes часто выдаются как hard facts без оговорки
- consolidation почти никогда не дает ничего полезного

## 4. Наборы тестов, которые я бы собрал

### A. Explicit facts
Проверяем:
- preference extraction
- identity facts
- technology usage
- constraint extraction

### B. Knowledge updates
Проверяем:
- “раньше было X, теперь Y”
- replace vs coexist
- status changes
- due date changes

### C. Temporal reasoning
Проверяем:
- relative dates -> absolute dates
- valid_from / valid_to
- “used to”, “now”, “from now on”

### D. Tasks
Проверяем:
- request vs commitment
- open / done / cancelled
- deadline normalization

### E. Notes
Проверяем:
- design direction
- rationale / trade-offs
- evolving plan
- lesson/pattern
- doc digest

### F. Rationale answers
Вопросы вида:
- “к чему мы склонялись?”
- “почему решили именно так?”
- “какая была суть обсуждения?”
- “что уже обсуждали про этот паттерн?”

### G. Conflict / abstention
Проверяем:
- conflicting claims
- conflicting notes
- weak evidence
- missing evidence

## 5. Золотая разметка

Для каждой test conversation / document полезно иметь gold annotations:

- `gold_sources`
- `gold_entities`
- `gold_claims`
- `gold_tasks`
- `gold_notes`
- `gold_profile_projection`
- `gold_answer_expectations`

Для `gold_notes` нужно отдельно размечать:
- note type
- what makes it reusable
- whether it should eventually consolidate into a claim/task/episode
- whether it should remain a note only

## 6. Набор regression tests

Я бы обязательно держал фиксированный набор кейсов:

1. preference change
2. contradiction with old claim
3. deadline update
4. design discussion -> note only
5. explicit fact + rationale in one turn
6. note consolidation to claim
7. note consolidation to task
8. question requiring note-first retrieval
9. question requiring claim-first retrieval
10. question requiring abstention

## 7. Что брать как внешние ориентиры

### LongMemEval-style buckets
Полезны для проверки:
- information extraction
- multi-session reasoning
- temporal reasoning
- knowledge updates
- abstention

### LoCoMo-style buckets
Полезны для:
- long multi-session dialogue setup
- event summarization
- long-horizon conversational memory

Но для `Note` все равно понадобится свой внутренний eval, потому что rationale-heavy memory там обычно представлена слабее, чем явные facts.

## 8. Suggested scoring

### Offline scoring
- claim precision / recall / F1
- task precision / recall / F1
- note precision / usefulness
- update correctness
- answer grounding score
- abstention accuracy

### Human review
Отдельно смотреть:
- был ли retrieval вообще полезен
- не перескочила ли система из note в ложный fact
- не слишком ли notes многословны
- не потерялась ли важная scope boundary

## 9. Practical rollout plan

### Stage 1 — shadow mode
- memory pipeline работает
- но не влияет на ответы
- сохраняем outputs и метрики

### Stage 2 — read-only influence
- используем retrieval в ответах
- но не даем памяти менять critical behavior

### Stage 3 — write enabled
- включаем structured writes
- следим за duplicate/contradiction rate
- ставим лимиты на number of notes/claims/tasks per turn

### Stage 4 — note consolidation
- включаем background consolidation
- проверяем, не ухудшает ли это claim precision

## 10. Простой go / no-go checklist

Я бы не выпускал систему шире, пока не выполнены условия:

- duplicate rate контролируем
- task explosion не наблюдается
- claim precision приемлемая
- note spam rate низкая
- rationale questions реально выигрывают от notes
- system умеет честно abstain
- есть понятный audit trail в `memory_runs`
