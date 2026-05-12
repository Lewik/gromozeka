# Memory Implementation

## Суперкратко

Мы строим не просто поиск по старым сообщениям и не просто векторную базу. Мы строим typed memory layer для LLM: система должна переживать границы одной сессии, выделять из источников устойчивые факты, заметки, задачи и профиль, а потом возвращать модели ровно тот срез памяти, который нужен под текущий запрос.

Центр этой системы - не одна база и не один retrieval-механизм, а явная модель памяти плюс ее жизненный цикл. У памяти есть типы (`Source`, `Claim`, `Note`, `Profile`, `Task`, позже `Episode`) и есть процессы `write`, `reconcile`, `retrieve`, `consolidate`, `repair`, `retain/delete`. Поэтому embeddings, full-text, графовые связи и сама база данных здесь не заменяют модель памяти, а обслуживают ее запись, поиск, обновление и объяснимость.

## Структура памяти

В `v3` память - это не одна таблица и не один список фактов. Это несколько слоев, плюс общие поля, которые проходят почти через все memory items.

### 1. Evidence layer

`Source` - сырой источник знания: chat turn, кусок документа, tool output, импортированная запись.

Это самый нижний слой памяти. Он нужен не для красивых ответов, а для доказуемости:
- откуда взялось знание
- что именно было сказано или увидено
- к чему можно вернуться при конфликте или сомнении

`Source` должен быть почти immutable: это след, а не интерпретация.

### 2. Семантическое ядро

`Entity` - канонический объект памяти: человек, проект, репозиторий, файл, технология, документ, сервис.

`Claim` - атомарное нормализованное утверждение об объекте. По смыслу это одно применение предиката к субъекту: "проект использует Postgres", "пользователь предпочитает русский", "дедлайн в пятницу".

Это главный слой для точных ответов, reconciliation и update-логики.

Важно:
- `Entity` отвечает на вопрос "о чем вообще речь"
- `Claim` отвечает на вопрос "что именно мы про это утверждаем"

### 3. Промежуточный semantic layer

`Note` - self-contained semantic fragment. Это уже не сырой источник, но еще и не обязательно hard fact.

`Note` нужен для:
- rationale
- direction
- evolving plans
- hypotheses
- document digests
- lessons and patterns
- contextual framing

Ключевая идея `v3`: не все полезное надо немедленно цементировать в `Claim`. Часть знаний сначала живет как `Note`, а потом может:
- остаться note надолго
- быть superseded
- созреть и сконсолидироваться в claims / tasks / profile / episodes

### 4. Операционные проекции

`Profile` - компактная проекция устойчивого контекста. Это не source of truth, а сжатая сборка из explicit instructions, stable claims и выбранного долгоживущего контекста.

`Task` - память о будущем действии. Это operational memory: что надо сделать, что обещано, что заблокировано, что завершено.

`Episode` - переносимый опыт, который появляется позже, не в earliest MVP. Это уже не просто факт и не просто note, а оформленный lesson вида "ситуация -> действие -> результат -> урок".

### 5. Связи и lineage

Важны не только сами memory items, но и связи между ними.

Основные типы связей в `v3`:
- `claim_sources` - из каких sources поддерживается claim
- `note_sources` - из каких sources собрана note
- `task_sources` - на чем основана task
- `note_links` - как notes связаны между собой: `supports`, `contradicts`, `refines`, `supersedes`, `derived_from`
- `entity_aliases` - разные имена одной и той же сущности
- lineage-поля вроде `origin_note_id`, `supersedes_claim_id`, `retracted_by_claim_id`

Это нужно, чтобы память можно было не только искать, но и объяснять, обновлять и чинить.

### 6. Поперечные измерения

Почти у любого memory item в `v3` есть не только текст и тип, но и общие измерения:

- `scope_text` и `scope_json`
  Это границы применимости памяти: к какому проекту, разговору, субъекту или окружению она относится.

- `truth status`
  Это статус знания как знания: `active`, `superseded`, `retracted`, `expired`, `candidate`.

- `retrieval_state`
  Это статус участия в поиске: `active`, `warm`, `cold`, `archived`, `deleted`.

- время и usage
  `valid_from`, `valid_to`, `first_seen_at`, `last_seen_at`, `use_count`, `last_used_at`, `last_validated_at`.

Важно: `truth status` и `retrieval_state` - разные вещи. Знание может быть уже неактуальным как истина, но еще важным для explainability. И наоборот, знание может быть истинным, но редко участвовать в top retrieval.

### 7. Audit layer

`MemoryRun` - журнал того, как память записывалась, читалась, чинилась и обновлялась.

Он хранит:
- какой pipeline сработал
- какой prompt и какая модель использовались
- что было retrieved
- какой budget выделили
- какие ops предложили
- какие ops реально применились
- где процесс сломался

Это не "память о мире", а память о работе самой memory system.

### 8. Что не является памятью

`PredicateCatalog` важен, но сам памятью не является. Это словарь правил, который говорит:
- какие бывают predicates
- как их сравнивать
- когда claims сосуществуют, а когда вытесняют друг друга
- что должно попадать в `Profile` или `Task`

Если очень грубо:

- `Source` - что было сказано или увидено
- `Entity` - о чем идет речь
- `Claim` - что именно утверждается
- `Note` - что уже важно, но еще не обязано быть жестким фактом
- `Profile` / `Task` / `Episode` - проекции памяти под работу, действия и опыт
- source-links, lineage, scope, statuses - то, что делает память управляемой
- `MemoryRun` - след работы самой memory system

## Сущности и поля

Ниже уже более подробная карта `v3`. Это не буквальный SQL-дамп, а логическая модель. Поэтому я пишу не `owner_entity_id`, а `owner: Entity`; не `namespace_id`, а `namespace: Namespace`. На уровне хранения это, конечно, потом превратится в id, foreign key и json-поля.

### Общие понятия

#### `Namespace`

`Namespace` - это отдельное пространство памяти. Грубо говоря, контейнер, внутри которого живут все memory items.

Зачем нужен:
- чтобы память разных пользователей, проектов или сред не смешивалась;
- чтобы retrieval, retention и delete работали в правильных границах;
- чтобы можно было забыть или архивировать память по одному контуру, не трогая другой.

Минимально про `Namespace` надо понимать одно:
- почти любая сущность ниже живет внутри какого-то одного namespace.

#### `Scope`

`Scope` - это границы применимости памяти.

Обычно у него две части:
- `text`
  Человеческое объяснение, к чему это относится.
- `data`
  Машинно-читаемый envelope, где обычно лежат:
  `scope_kind`, `project`, `conversation`, `subject`, `environment`, `modality`, `audience`.

Зачем нужен:
- чтобы не переобобщать память;
- чтобы note про один проект не начала влиять на другой;
- чтобы reconciliation и retrieval работали предсказуемо.

#### `TruthStatus`

`TruthStatus` отвечает на вопрос, актуально ли это как знание.

Типичные значения:
- `active`
- `superseded`
- `retracted`
- `expired`
- `candidate`

#### `RetrievalState`

`RetrievalState` отвечает на вопрос, как часто item должен участвовать в поиске.

Типичные значения:
- `active`
- `warm`
- `cold`
- `archived`
- `deleted`

Важно:
- `TruthStatus` и `RetrievalState` - разные вещи.
- Знание может быть уже неактуальным как истина, но все еще полезным для explainability.

### `Source`

`Source` хранит сырой источник знания.

Поля:
- `namespace: Namespace`
  В каком пространстве памяти живет источник.
- `type`
  Что это такое: chat turn, tool output, imported note, external record.
- `conversation`
  Из какого разговора это пришло, если источник чатовый.
- `turn`
  Из какого конкретного хода или сообщения это пришло.
- `speaker_role`
  Кто это произвел: user, assistant, tool, system, external.
- `author_label`
  Человекочитаемая подпись автора, если она есть.
- `content`
  Сырой текст или содержимое источника.
- `content_hash`
  Хэш содержимого для dedupe и retry safety.
- `observed_at`
  Когда это событие реально произошло или было замечено.
- `created_at`
  Когда запись попала в memory storage.
- `metadata`
  Дополнительный метаконтекст: origin, format, channel, import data и т.д.
- `retention_class`
  Как его хранить: обычный, короткоживущий, чувствительный, импортированный.
- `expires_at`
  Когда источник должен перестать жить по policy.
- `deleted_at`
  Когда был мягко удален.
- `embedding`
  Векторное представление, если его вообще считаем нужным.

Ключевая мысль:
- `Source` почти immutable.
- Это не интерпретация, а след.

### `MemoryRun`

`MemoryRun` - audit trail и debug слой. Он описывает не знание о мире, а знание о том, как работала memory system.

Поля:
- `namespace: Namespace`
  В каком пространстве памяти выполнялся run.
- `run_type`
  Какой этап выполнялся: route, retrieve_update, canonicalize, construct_notes, reconcile_notes, extract_claims, reconcile_claims, update_profile, update_tasks, consolidate_notes, repair_memory, apply_retention, read_plan, compose_answer, compact.
- `trigger_mode`
  Hot path, background, manual, slow path.
- `sources: [Source]`
  Какие источники участвовали в этом запуске.
- `retrieved_items`
  Какие memory items были подняты retrieval'ом.
- `retrieval_budget`
  Какой budget был выделен.
- `prompt_name`
  Имя prompt.
- `prompt_version`
  Версия prompt.
- `model_name`
  Какая модель использовалась.
- `input_hash`
  Хэш входа для идемпотентности и retry safety.
- `output`
  Сырой результат этапа.
- `applied_ops`
  Какие операции реально применили.
- `repair_actions`
  Какие repair-действия были сделаны.
- `latency_ms`
  Длительность.
- `token_input`
  Сколько токенов ушло на вход.
- `token_output`
  Сколько токенов пришло на выходе.
- `status`
  Success, failed, partial.
- `error`
  Текст ошибки, если она была.
- `created_at`
  Когда run был создан.

Ключевая мысль:
- без `MemoryRun` система памяти теряет explainability и дебажность.

### `Entity`

`Entity` - канонический объект памяти, к которому привязываются claims, notes, tasks и episodes.

Поля:
- `namespace: Namespace`
  В каком пространстве памяти живет сущность.
- `type`
  Тип сущности: person, agent, organization, project, repo, file, technology, product, location, concept, document, conversation, service.
- `canonical_name`
  Основное каноническое имя.
- `normalized_name`
  Нормализованная форма имени для поиска и merge.
- `summary`
  Короткое описание сущности.
- `status`
  Состояние сущности: active, merged, deleted.
- `merged_into: Entity`
  На какую сущность она указывает после merge, если это дубль.
- `attributes`
  Свободные атрибуты, которые не хочется раскладывать в отдельные claims.
- `first_seen_at`
  Когда сущность впервые появилась в памяти.
- `last_seen_at`
  Когда ее последний раз видели.
- `created_at`
  Когда сущность была создана в storage.
- `updated_at`
  Когда она обновлялась.
- `aliases: [EntityAlias]`
  Альтернативные имена сущности.

#### `EntityAlias`

`EntityAlias` - связь между сущностью и одним ее альтернативным именем.

Поля:
- `text`
  Альтернативное имя.
- `normalized_text`
  Нормализованная форма alias.
- `source: Source`
  Из какого источника alias был замечен.
- `confidence`
  Насколько уверенно считаем alias валидным.
- `created_at`
  Когда alias был записан.

Ключевая мысль:
- `Entity` отвечает на вопрос "что это за объект".
- Это якорь памяти, а не утверждение.

### `PredicateCatalog`

`PredicateCatalog` - служебная сущность, а не memory item. Она нужна, чтобы claims можно было сравнивать и обновлять по правилам, а не на глаз.

Поля:
- `predicate`
  Имя предиката.
- `description`
  Человеческое описание смысла предиката.
- `subject_type`
  Какой тип сущности может быть субъектом.
- `object_kind`
  Какой объект ожидается: entity, string, number, boolean, json.
- `cardinality`
  Single или multi.
- `temporal_policy`
  Temporal semantics: atemporal, time_scoped, status_like.
- `conflict_policy`
  Что делать при конфликте: replace, coexist, range_split.
- `profile_sync`
  Должен ли этот предикат попадать в `Profile`.
- `task_sync`
  Должен ли этот предикат влиять на `Task`.
- `default_importance`
  Базовая важность.
- `active`
  Используется ли предикат сейчас.

Ключевая мысль:
- без `PredicateCatalog` claims быстро становятся неуправляемыми.

### `Note`

`Note` - промежуточный semantic fragment. Это уже полезный смысл, но еще не обязательно точный hard fact.

Поля:
- `namespace: Namespace`
  В каком пространстве памяти живет note.
- `type`
  decision, direction, hypothesis, plan, lesson, doc_digest, context.
- `title`
  Короткий заголовок.
- `summary`
  Сам self-contained semantic fragment.
- `scope: Scope`
  Где эта note применима.
- `status`
  Состояние note как знания: active, superseded, retracted, resolved, stale, candidate.
- `retrieval_state: RetrievalState`
  Насколько активно note должна участвовать в retrieval.
- `maturity_state`
  Fresh, stabilizing, mature, consolidated.
- `maturity_score`
  Числовая оценка зрелости.
- `anchor: Entity`
  Основная сущность, вокруг которой note построена.
- `keywords`
  Ключевые слова для retrieval.
- `tags`
  Дополнительные теги.
- `candidate_claims`
  Подсказки, во что note потенциально может консолидироваться.
- `metadata`
  Служебный метаконтекст.
- `confidence`
  Уверенность в корректности note.
- `importance`
  Важность для retrieval и приоритизации.
- `valid_from`
  С какого момента note считается применимой.
- `valid_to`
  До какого момента note применима.
- `supersedes: Note`
  Какую note эта note вытесняет.
- `created_by_run: MemoryRun`
  Каким memory run note была создана.
- `created_at`
  Когда появилась запись.
- `updated_at`
  Когда обновлялась.
- `embedding`
  Вектор для retrieval, если он используется.
- `use_count`
  Сколько раз note реально участвовала в retrieval.
- `last_used_at`
  Когда использовалась в последний раз.
- `last_validated_at`
  Когда note в последний раз проверяли или подтверждали.
- `evidence_count`
  Сколько sources ее поддерживают.
- `archived_at`
  Когда note была архивирована.
- `entities: [NoteEntity]`
  Какие сущности связаны с note и в какой роли.
- `sources: [NoteSource]`
  На каких источниках note основана.
- `links: [NoteLink]`
  Как note связана с другими notes.

#### `NoteEntity`

Поля:
- `entity: Entity`
  Какая сущность участвует.
- `role`
  Ее роль внутри note: primary, secondary, mentioned, owner, subject.

#### `NoteSource`

Поля:
- `source: Source`
  Из какого источника note собрана.
- `support_type`
  direct, summarized, imported.
- `evidence_span`
  Где именно внутри source лежит опора.
- `evidence_quote`
  Короткая цитата.
- `support_confidence`
  Уверенность в этой связи.

#### `NoteLink`

Поля:
- `target: Note`
  На какую другую note идет связь.
- `type`
  supports, contradicts, refines, related, supersedes, derived_from.
- `weight`
  Вес связи.

Ключевая мысль:
- `Note` нужна для rich retrieval, rationale и evolving context.
- Она может жить долго и не обязана превращаться в claim.

### `Claim`

`Claim` - атомарное нормализованное утверждение. Это основной объект для точных ответов и reconciliation.

Поля:
- `namespace: Namespace`
  В каком пространстве памяти живет claim.
- `subject: Entity`
  О ком или о чем идет утверждение.
- `predicate`
  Нормализованный предикат из `PredicateCatalog`.
- `object_entity: Entity`
  Объект утверждения, если он сущностный.
- `object_value`
  Объект утверждения, если это literal или json, а не сущность.
- `text`
  Человекочитаемая stand-alone формулировка claim.
- `context`
  Дополнительный контекст, если он нужен.
- `scope: Scope`
  Где claim применима.
- `qualifiers`
  Условия, степени, модальности и другие уточнения.
- `confidence`
  Уверенность в claim.
- `importance`
  Важность claim.
- `status: TruthStatus`
  Active, superseded, retracted, expired, candidate.
- `retrieval_state: RetrievalState`
  Active, warm, cold, archived, deleted.
- `valid_from`
  С какого момента claim действует.
- `valid_to`
  До какого момента действует.
- `origin_note: Note`
  Если claim родился из note consolidation, это связь назад.
- `first_seen_at`
  Когда впервые увидели этот факт.
- `last_seen_at`
  Когда последний раз подтверждали или встречали.
- `supersedes: Claim`
  Какой claim этот claim вытеснил.
- `retracted_by: Claim`
  Каким claim этот claim был опровергнут.
- `created_by_run: MemoryRun`
  Каким memory run claim была создана.
- `created_at`
  Когда записана.
- `updated_at`
  Когда обновлялась.
- `embedding`
  Вектор для retrieval, если нужен.
- `use_count`
  Как часто claim реально использовалась.
- `last_used_at`
  Когда использовали в последний раз.
- `last_validated_at`
  Когда в последний раз проверяли.
- `archived_at`
  Когда архивировали.
- `sources: [ClaimSource]`
  На каких sources claim основана.

#### `ClaimSource`

Поля:
- `source: Source`
  На каком источнике основана claim.
- `support_type`
  direct, inferred, imported, derived_from_note.
- `evidence_span`
  Где именно внутри source лежит опора.
- `evidence_quote`
  Короткая цитата.
- `support_confidence`
  Насколько уверенно считаем связь корректной.

Ключевые правила:
- один claim = одно нормализованное утверждение;
- claim должен быть понятен без исходного чата;
- если смысл еще слишком мягкий, лучше оставить `Note`.

### `Profile`

`Profile` - compact always-in-context projection. Это не source of truth, а короткая проекция устойчивого контекста.

Поля:
- `namespace: Namespace`
  В каком пространстве памяти живет profile.
- `owner: Entity`
  Для кого этот profile построен.
- `data`
  Структурированное содержимое профиля.
- `text`
  Короткий человекочитаемый рендер профиля.
- `version`
  Версия profile.
- `updated_by_run: MemoryRun`
  Каким memory run профиль обновили.
- `last_compacted_at`
  Когда профиль в последний раз пересобирали.
- `created_at`
  Когда профиль появился.
- `updated_at`
  Когда обновился.

Обычно внутри `data` живут:
- `preferred_name`
- `languages`
- `timezone`
- `response_preferences`
- `expertise`
- `stable_preferences`
- `constraints`
- `current_goals`
- `soft_signals`

Ключевая мысль:
- `Profile` должен быть коротким.
- Спорные и временные вещи лучше держать в claims/notes, а не тащить в profile.

### `Task`

`Task` - operational memory про то, что должно быть сделано.

Поля:
- `namespace: Namespace`
  В каком пространстве памяти живет task.
- `owner: Entity`
  Чей это task с точки зрения владельца.
- `assignee: Entity`
  Кому task назначен.
- `title`
  Короткий заголовок задачи.
- `description`
  Подробности, если они нужны.
- `status`
  Open, in_progress, blocked, done, cancelled.
- `priority`
  Low, normal, high.
- `due_at`
  Дедлайн, если он есть.
- `scope: Scope`
  Где задача применима.
- `acceptance_criteria`
  Как понять, что задача выполнена.
- `blockers`
  Что мешает выполнению.
- `related_entities: [Entity]`
  Какие сущности с задачей связаны.
- `origin_note: Note`
  Если задача выросла из note.
- `created_by_run: MemoryRun`
  Каким memory run создана.
- `confidence`
  Насколько уверенно считаем это реальной задачей.
- `retrieval_state: RetrievalState`
  Active, warm, cold, archived, deleted.
- `created_at`
  Когда создана.
- `updated_at`
  Когда обновлялась.
- `closed_at`
  Когда была закрыта.
- `use_count`
  Как часто task участвовала в retrieval.
- `last_used_at`
  Когда использовали в последний раз.
- `archived_at`
  Когда архивировали.
- `sources: [TaskSource]`
  На каких sources задача основана.

#### `TaskSource`

Поля:
- `source: Source`
  На каком источнике основана задача.
- `support_type`
  Direct, derived_from_note, imported.
- `evidence_span`
  Где внутри source лежит опора.
- `evidence_quote`
  Короткая цитата.
- `support_confidence`
  Уверенность в связи.

Ключевые правила:
- вопрос сам по себе не равен task;
- request не всегда равен commitment;
- один task = одно понятное обязательство.

### `Episode`

`Episode` - переносимый опыт. Это более поздний тип памяти, который появляется, когда система уже умеет хранить reusable lessons.

Поля:
- `namespace: Namespace`
  В каком пространстве памяти живет episode.
- `owner: Entity`
  Для кого этот опыт релевантен.
- `situation`
  В какой ситуации это произошло.
- `action`
  Что было сделано.
- `result`
  Чем это закончилось.
- `lesson`
  Какой вывод или урок из этого вынесли.
- `tags`
  Теги для retrieval.
- `success_score`
  Насколько это был успешный паттерн.
- `origin_note: Note`
  Из какой note episode вырос.
- `created_by_run: MemoryRun`
  Каким memory run создан.
- `sources: [Source]`
  Из каких источников episode был собран.
- `retrieval_state: RetrievalState`
  Active, warm, cold, archived, deleted.
- `created_at`
  Когда создан.
- `updated_at`
  Когда обновлялся.
- `embedding`
  Вектор для retrieval.
- `use_count`
  Как часто использовался.
- `last_used_at`
  Когда использовали в последний раз.
- `archived_at`
  Когда архивировали.

Ключевая мысль:
- `Episode` - это lesson, а не transcript и не chain-of-thought.

## Жизненный цикл памяти

На верхнем уровне у памяти три контура:

- `write` - система принимает новый сигнал и решает, надо ли вообще что-то записывать;
- `background maintenance` - система позже уплотняет, чинит, архивирует и удаляет память;
- `read / recall` - система под текущий запрос достает нужный срез памяти.

Если совсем коротко, общий цикл такой:

`source -> route -> retrieve context -> reconcile -> apply -> consolidate / repair / retain -> retrieve for answer`

### 1. Запись начинается всегда с `Source`

Любой новый значимый вход сначала сохраняется как `Source`: сообщение, tool output, документ, импортированная запись.

Это важно по двум причинам:
- сначала мы фиксируем evidence;
- только потом делаем интерпретацию.

То есть память не начинается с `Claim` или `Note`. Она начинается с сырого следа, к которому потом можно вернуться.

### 2. Потом memory router решает, есть ли тут память

После сохранения `Source` система не обязана немедленно что-то записывать в semantic layer. Сначала работает router. Он выбирает один из путей:

- `noop`
  Ничего не записываем, если сигнал шумный, одноразовый или не тянет на память.
- `direct_structured_write`
  Сразу идем в `Claim` / `Task` / `Profile`, если информация уже явная и нормализуемая.
- `note_write`
  Идем в `Note`, если информация важная, но еще слишком контекстная или сырая для hard fact.
- `mixed`
  Обычный knowledge-work кейс: одна и та же реплика дает немного structured memory и одну осмысленную `Note`.

Это одна из ключевых идей `v3`: не каждый полезный кусок должен проходить через `Note`, и не каждый кусок должен становиться `Claim`.

### 3. Перед записью идет write-time retrieval

Новая память не пишется "в пустоту". Перед extraction и update система подтягивает соседний контекст:

- близкие `Claim`
- активные `Task`
- `Profile`
- релевантные `Note`
- при необходимости сами `Source`

Зачем это нужно:
- не плодить дубликаты;
- не спорить с уже существующей памятью вслепую;
- обновлять нужный объект, а не создавать новый;
- видеть scope и историю изменений до записи.

После retrieval идет canonicalization: система приводит сущности к каноническому виду и понимает, что "Postgres", "postgresql" и "наша база" могут относиться к одной `Entity`.

### 4. На write path память не просто создается, а reconcile'ится

После extraction система не должна тупо делать `insert`. Она сравнивает новое знание с тем, что уже есть, и выбирает осмысленное действие:

- создать новый memory item;
- обновить существующий;
- сделать `supersede`;
- сделать `retract`;
- оставить `noop`;
- просто прикрепить еще один `Source` как поддержку.

Поэтому важна сама последовательность:

`Source -> retrieval -> canonicalization -> extraction -> reconciliation -> transaction`

Отдельно важно:
- `Profile` - это projection, а не первичный источник истины;
- `Task` нельзя создавать только потому, что пользователь о чем-то спросил;
- `mixed` не означает "сохраним всё дважды".

### 5. После записи память продолжает жить в background workflows

Записать memory item один раз недостаточно. Дальше у памяти есть обслуживающие процессы.

#### `Consolidation`

Это путь, который переводит mature `Note` в более жесткие формы, если сигнал созрел. Обычно система:

- выбирает mature notes;
- подтягивает связанные claims / tasks / notes / profile;
- решает, можно ли родить `Claim`, обновить `Task`, скорректировать `Profile` или позже собрать `Episode`;
- меняет статус самой note: оставить активной, пометить как `resolved`, `stale`, `superseded` или `consolidated`.

Ключевая мысль:
- не каждая `Note` обязана стать `Claim`;
- часть notes может оставаться notes очень долго или навсегда.

#### `Repair`

Это отдельный workflow для деградации памяти. Он ищет и чинит:

- duplicate notes;
- contradictory active claims;
- stale tasks;
- profile drift;
- bad entity merges;
- scope leakage;
- note spam.

`Repair` не должен бесшумно переписывать историю. Он должен быть evidence-backed и audit-friendly.

#### `Retention / forgetting`

Это policy-слой, а не уборка "когда руки дошли". Он решает:

- что держать горячим;
- что перевести в `warm` или `cold`;
- что архивировать;
- что удалять по policy или явному forget/delete request.

Здесь важно не путать:
- `truth state` - актуально ли это как знание;
- `retrieval state` - как активно это участвует в поиске и хранении.

Можно иметь:
- активный, но архивный по retrieval item;
- superseded item, который все еще нужен для lineage и аудита.

### 6. Вспоминание - это отдельный read workflow

Когда приходит новый запрос, система сначала решает, нужен ли вообще memory retrieval. Если вопрос:

- history-aware;
- user-specific;
- task-aware;
- rationale-heavy;
- temporal;
- high-stakes;

тогда включается read path.

Обычно он выглядит так:

1. подтянуть core blocks: `Profile`, active `Task`, иногда короткий session summary;
2. выбрать режим ответа: `factual`, `rationale`, `task`, `mixed`;
3. выбрать retrieval policy: `claim-first`, `note-first`, `mixed`, `evidence-first`;
4. достать bounded набор memory items;
5. при конфликте или сомнении спуститься к `Source`.

Здесь bounded retrieval принципиален: система не должна тащить в prompt все подряд. Она должна достать маленький, релевантный и объяснимый срез памяти под конкретный вопрос.

### 7. Самая короткая формула

Если свернуть весь lifecycle в одну мысль, то она такая:

- `Source` фиксирует, что произошло;
- semantic layer решает, что это значит;
- background workflows решают, что с этим станет со временем;
- retrieval решает, что из этого реально надо вспомнить сейчас.


## Зачем хранить источник

Потому что память без источника быстро превращается в недоказуемую кашу. Потом уже непонятно, это пользователь сам сказал, агент додумал, это было давно или только что, это точная формулировка или вольный пересказ. В `v3` источник - это первичный evidence layer, а не просто вспомогательная ссылка.

Одного URL почти всегда мало. Источник у нас часто вообще не URL, а конкретное сообщение, кусок переписки, локальный файл или фрагмент документа. Даже если URL есть, страница потом может поменяться.

Поэтому память должна ссылаться не просто на "откуда-то взято", а на конкретный след: сообщение, документ, цитату, фрагмент, время. Это нужно для re-check, explainability, разрешения противоречий, evidence-fallback при спорных ответах и безопасного удаления или архивирования памяти по policy.
