# 07. Evaluation Plan

## 1. Зачем нужен отдельный eval

У memory layer почти всегда есть ложное чувство “ну вроде работает”.
На практике деградация приходит тихо:

- claim precision падает
- stale claims растут
- note layer превращается в summary-spam
- task extraction начинает плодить мусор
- rationale answers звучат уверенно, но слабо grounded
- retrieval начинает перетаскивать слишком много контекста
- repair и retention не успевают за ростом памяти

Поэтому memory систему надо мерить отдельно от “качества ответа в среднем”.

## 2. Что именно мерить

### 2.1 Structured write quality
- claim precision
- claim recall на явных facts
- task precision / recall
- profile projection accuracy
- duplicate rate
- contradiction rate
- stale-memory rate

### 2.2 Note quality
- note precision
- note novelty rate
- note retrieval usefulness
- note spam rate
- scope correctness for notes

### 2.3 Update and lifecycle quality
- supersede correctness
- retract correctness
- temporal normalization correctness
- correct coexist vs replace decisions
- note -> claim consolidation accuracy
- note -> task consolidation accuracy
- retrieval_state downgrade correctness
- archive/delete correctness

### 2.4 Read quality
- factual answer grounding
- rationale answer grounding
- correct note-first vs claim-first retrieval choice
- abstention quality
- conflict handling quality
- scope-boundary preservation

### 2.5 Systems metrics
- write latency
- read latency
- token cost
- background job cost
- retrieval hit rate
- percentage of `noop`
- note generation rate per 100 turns
- retrieval budget overflow rate
- repair action rate

## 3. Отдельно про `Note` layer

`Note` надо проверять отдельно, иначе легко получить красивый, но бесполезный слой.

### Note-specific metrics
- **note precision** — действительно ли note содержит переиспользуемый semantic fragment
- **note novelty rate** — не дублирует ли note уже существующую note/source
- **note spam rate** — сколько notes ни разу не пригодились
- **note retrieval usefulness** — помогла ли note ответить на rationale/history question
- **consolidation yield** — сколько useful claims/tasks/episodes реально получилось из notes
- **maturity calibration** — совпадает ли `maturity_state` с тем, насколько note реально готов к consolidation

### Красные флаги
- notes слишком похожи на summary исходных turns
- notes слишком длинные и не self-contained
- notes слишком много
- notes часто выдаются как hard facts без оговорки
- consolidation почти никогда не дает ничего полезного
- notes не уважают scope boundaries

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

### H. Scope leakage
Проверяем:
- проектный факт не утек в global
- environment-specific факт не стал universal
- note не переобобщился через conversation boundary

### I. Retention / forgetting
Проверяем:
- low-value items уходят из hot retrieval
- archive не ломает explainability
- delete request удаляет то, что должен удалить

## 5. Золотая разметка

Для каждой test conversation / document полезно иметь gold annotations:

- `gold_sources`
- `gold_entities`
- `gold_claims`
- `gold_tasks`
- `gold_notes`
- `gold_profile_projection`
- `gold_answer_expectations`

Для `gold_notes` полезно отдельно размечать:
- note type
- what makes it reusable
- whether it should eventually consolidate into a claim/task/episode
- whether it should remain note-only
- expected scope

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
11. scope leakage prevention
12. retrieval budget cap respected
13. archive without truth corruption
14. explicit forget/delete request

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
- long-range conversational memory

### Note / consolidation buckets
Это уже свой внутренний eval:
- rationale retrieval
- note usefulness
- note maturity
- note spam control

## 8. Suggested scoring

### Offline scoring
- claim precision / recall / F1
- task precision / recall / F1
- note precision / usefulness
- update correctness
- answer grounding score
- abstention accuracy
- scope leakage rate
- retrieval budget compliance
- retention correctness

### Human review
Отдельно смотреть:
- был ли retrieval вообще полезен
- не перескочила ли система из note в ложный fact
- не слишком ли notes многословны
- не потерялась ли важная scope boundary
- не слишком ли агрессивен repair

## 9. Practical rollout plan

### Stage 1 — shadow mode
- memory pipeline работает
- но не влияет на ответы
- сохраняем outputs и метрики

### Stage 2 — read-only influence
- retrieval влияет на ответы
- но structured writes еще под строгим review

### Stage 3 — write enabled
- включаем structured writes
- следим за duplicate / contradiction / budget overflow rate
- ставим caps на number of notes/claims/tasks per turn

### Stage 4 — note consolidation
- включаем background consolidation
- проверяем, не ухудшает ли это claim precision

### Stage 5 — repair and retention
- включаем repair
- включаем archive / delete policy
- проверяем, не теряется ли полезная explainability

## 10. Простой go / no-go checklist

Я бы не выпускал систему шире, пока не выполнены условия:

- duplicate rate контролируем
- task explosion не наблюдается
- claim precision приемлемая
- note spam rate низкая
- rationale questions реально выигрывают от notes
- system умеет честно abstain
- retrieval budget соблюдается
- repair не ломает history
- retention / archive policy объяснима
- есть понятный audit trail в `memory_runs`
