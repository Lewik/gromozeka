# 07. Evaluation Plan

## 1. Зачем нужен отдельный eval

Без eval memory system очень легко выглядит “почти рабочим”, пока не начинаешь ловить:
- дубли
- stale facts
- перепутанные time windows
- profile drift
- уверенные, но ложные ответы на основе старой памяти

Поэтому memory надо тестировать как отдельный subsystem, а не только “по ощущениям” в диалоге.

## 2. Что мерить

### 2.1 Write precision
Вопрос:
из того, что система записала, какая доля реально стоила записи?

Практический способ:
- случайная выборка new claims/tasks/profile updates
- ручная разметка: `useful / borderline / noise`

### 2.2 Write recall
Вопрос:
из реально полезных долговременных фактов какая доля попала в память?

Практический способ:
- собрать gold annotations на длинных диалогах
- сравнить с тем, что записала система

### 2.3 Duplicate rate
Вопрос:
как часто система пишет фактически одно и то же знание повторно?

### 2.4 Conflict rate
Вопрос:
как часто в активной памяти одновременно лежат взаимоисключающие claims для одного scope?

### 2.5 Temporal accuracy
Вопрос:
умеет ли система корректно обновлять `valid_from/valid_to`, а не просто затирать историю?

### 2.6 Retrieval recall@k
Вопрос:
попадает ли нужная память в top-k retrieval?

### 2.7 Grounded answer accuracy
Вопрос:
умеет ли агент ответить правильно, когда нужная память уже есть?

### 2.8 Abstention quality
Вопрос:
умеет ли агент честно сказать “памяти недостаточно”, а не дофантазировать?

### 2.9 Profile compactness
Вопрос:
остается ли профиль коротким и полезным?

### 2.10 Task state accuracy
Вопрос:
насколько верно система ведет lifecycle задач:
- open
- in_progress
- blocked
- done
- cancelled

## 3. Gold test set categories

### Category A. Information extraction
Проверяем:
- извлекаются ли устойчивые факты и preferences
- не сохраняется ли мусор

### Category B. Multi-session reasoning
Проверяем:
- умеет ли система использовать знания из прошлых сессий

### Category C. Temporal reasoning
Проверяем:
- “раньше / сейчас / больше не / с завтрашнего дня”

### Category D. Knowledge updates
Проверяем:
- supersede / retract / range_split
- single-valued preferences
- changing goals and current stack

### Category E. Abstention
Проверяем:
- честное “не знаю / не нашел в памяти”
- отсутствие invented specifics

### Category F. User modeling
Проверяем:
- response language
- detail level
- tone
- code style preferences
- stable constraints

### Category G. Task memory
Проверяем:
- commitments
- due dates
- closed/cancelled tasks
- duplicate tasks

## 4. Suggested offline eval harness

### Input
- recorded multi-session dialogues
- imported docs/tool outputs
- expected memory items
- expected answers to memory-dependent questions

### Pipeline
1. replay sources into a clean DB
2. run write path
3. inspect stored memory
4. ask read-time benchmark questions
5. score retrieval and answers

### Output
- write precision/recall
- retrieval recall@k
- answer accuracy
- abstention precision
- duplicate/conflict statistics

## 5. Example benchmark questions

### Extraction
- “What language does the user want responses in?”
- “What language should code comments use?”
- “What technologies does the user currently work with?”

### Temporal
- “What does the user currently work with?”
- “What did the user use before?”
- “Did the preference change after date X?”

### Profile
- “How detailed should the assistant answer by default?”
- “What user constraints should shape code output?”

### Task
- “What open tasks remain?”
- “Which task is blocked?”
- “Was the documentation task completed?”

### Abstention
- “What is the user’s favorite database?” when this was never stated

## 6. Human review checklist

Для случайной выборки memory writes reviewer отмечает:

### Claim
- atomic?
- reusable?
- supported by evidence?
- correctly time-normalized?
- duplicate?
- wrong scope?
- should have been a task or profile field instead?

### Profile
- concise?
- stable?
- missing important durable preferences?
- polluted by transient content?

### Task
- really actionable?
- correct assignee?
- correct status?
- due date normalized?

## 7. Acceptance thresholds for MVP

Это не абсолютная истина, но как старт я бы целился примерно сюда:

- write precision: `>= 0.85`
- duplicate rate among active claims: `<= 0.05`
- active conflict rate for single-valued predicates: `<= 0.02`
- retrieval recall@10 on memory-dependent questions: `>= 0.90`
- abstention precision: `>= 0.90`
- profile length stable over time without monotonic bloat

## 8. Regression suite

После каждого изменения prompt'ов или reconciliation policy запускать:

- same replay dataset
- same benchmark questions
- diff of stored claims
- diff of profile_json
- diff of task states
- diff of final answers

### Important
Сравнивать надо не только ответы, но и саму память.
Иногда ответы вроде не испортились, а memory store уже начал гнить.

## 9. Online metrics in production

### Write-side
- writes per 100 sources
- noop ratio
- invalid JSON ratio
- memory run failures
- average extraction latency
- average claims per write

### Read-side
- memory search latency
- percent of answers that used memory
- abstention rate
- user corrections that imply stale memory

### Health metrics
- active claims growth
- duplicate growth
- profile size drift
- compaction lag
- retraction/supersede ratio

## 10. Synthetic tests worth adding

### Temporal rewrite test
Диалог:
- “I currently use X”
- later: “I switched to Y”
Ожидание:
- active Y
- X not lost silently
- read query “currently” returns Y

### Preference update test
Диалог:
- “Answer in English”
- later: “Answer in Russian”
Ожидание:
- single active current preference
- profile updated
- old preference superseded

### Noisy chat test
Диалог:
- small talk + jokes + filler
Ожидание:
- almost nothing written

### Task lifecycle test
Диалог:
- request
- accepted
- progress update
- done
Ожидание:
- one task with correct state transitions

## 11. Benchmark inspirations

Полезно строить internal eval вокруг таких capability buckets:
- information extraction
- multi-session reasoning
- temporal reasoning
- knowledge updates
- abstention
- long conversational memory
- user modeling

Даже если не использовать внешние benchmark'и напрямую, эти buckets очень полезны как структура для внутренних тестов.

## 12. Final recommendation

Самый полезный практический цикл такой:

1. собрать 30–100 реалистичных длинных диалогов
2. вручную разметить gold memory
3. регулярно replay'ить их в staging
4. смотреть diff memory store после каждого изменения prompt'ов
5. только потом крутить online traffic

Это намного дешевле, чем отлавливать memory corruption уже в проде.
