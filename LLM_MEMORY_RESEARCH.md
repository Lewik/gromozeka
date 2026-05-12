# Исследование: память и представление знаний для LLM

Дата среза исследования: 2026-04-11

Цель: ответить на вопрос "в каком языке / представлении хранить знания, чтобы LLM потом могла их корректно вспоминать по запросу?"

Важная рамка:
- Это не записка про выбор конкретной БД.
- Главный вопрос здесь не "Postgres или Neo4j", а представление знаний, их консолидация, индексация, обновление и recall.
- Я специально разделяю классическое `knowledge representation` и инженерную `memory architecture for LLM`. Они пересекаются, но это не одно и то же.
- Вендорские доки полезны как инженерные сигналы, но не как окончательная теория.

## Короткий вывод

Если сжать все в несколько тезисов:

- Единого универсального "языка знаний" в духе обычной грамматики не существует.
- Для машинных систем знания обычно распадаются на комбинацию:
  - сущностей
  - отношений
  - типизированных атрибутов
  - правил
  - времени
  - provenance
  - confidence
- Для LLM почти никогда не выигрывает один-единственный формат.
- Рабочий паттерн в 2025-2026 годах - это гибридная память:
  - сырые эпизоды / события
  - извлеченные факты
  - заметки / notes
  - связи / graph layer
  - procedural memory
  - retrieval indexes
- Чистый vector RAG слишком слаб для:
  - temporal updates
  - contradiction handling
  - provenance-sensitive ответов
  - multi-hop recall
- Чистый knowledge graph тоже не серебряная пуля:
  - дорого строить
  - слишком легко потерять нюанс
  - не все полезное естественно ложится в triples
- Самая практичная стартовая архитектура, если писать свое:
  - Postgres
  - JSONB для типизированных memory objects
  - pgvector для dense retrieval
  - full-text / BM25 для lexical retrieval
  - явная temporal validity
  - явный provenance
  - при необходимости graph edges сверху

Мой самый жесткий вывод:

- Не надо начинать с "давайте выберем vector DB".
- Не надо начинать с "давайте поднимем graph DB и все запишем в triples".
- Надо начинать с `Memory IR` и операций над памятью.

## 1. Классический вопрос: что вообще считать "языком знаний"?

Когда говорят "в каком языке записывать знания?", обычно смешивают два уровня:

- `syntax` - как это сериализовано
- `semantics` - что эти утверждения значат и что из них можно вывести

Это принципиальное различие.

## 1.1 Основные семейства представления знаний

### Predicate Logic / First-Order Logic

Если отвечать на вопрос максимально фундаментально, то ближайший честный ответ такой:

Язык знаний растет из логики предикатов.

Базовые элементы:
- объекты / константы
- предикаты / отношения
- переменные
- кванторы
- логические связки

Пример:

```text
ParentOf(Alice, Bob).
ParentOf(Bob, Carol).

forall x, y:
  ParentOf(x, y) -> AncestorOf(x, y).

forall x, y, z:
  AncestorOf(x, y) and AncestorOf(y, z) -> AncestorOf(x, z).
```

Почему это важно:
- Это самый чистый формальный ответ на вопрос "какой язык у знания вообще?".
- Это conceptual ancestor почти всех структурированных KR-систем.

Почему этим редко пользуются напрямую в продукте:
- слишком тяжеловесно
- слабая ergonomics
- плохо работает как основной формат для messy real-world memory

### Datalog / rule systems

Это очень практичная логическая форма для фактов и правил.

Пример:

```prolog
parent(alice, bob).
parent(bob, carol).

ancestor(X, Y) :- parent(X, Y).
ancestor(X, Z) :- parent(X, Y), ancestor(Y, Z).
```

Почему это важно:
- естественно выражает inference
- хорошо подходит для "если ... то ..."
- полезно как слой правил поверх memory substrate

Почему этого недостаточно:
- само по себе не решает ingestion, provenance, retrieval и storage engineering

### RDF / RDFS / OWL

Это главное стандартное семейство semantic web.

Важно не путать:
- `RDF` - абстрактная модель графа утверждений
- `Turtle`, `JSON-LD`, `N-Triples` - синтаксис сериализации
- `RDFS` / `OWL` - семантика и онтологии
- `SPARQL` - запросы
- `SHACL` - валидация

Пример:

```turtle
@prefix ex: <http://example.org/> .

ex:Alice ex:parentOf ex:Bob .
ex:Bob ex:parentOf ex:Carol .
```

Почему это важно:
- это зрелые стандарты
- хорошо подходит для явной семантики, онтологий, linked data, interoperability
- provenance-паттерны там уже хорошо продуманы

Где болит:
- высокий modeling overhead
- OWL reasoning часто избыточен для memory-системы агента
- triples могут оказаться слишком ломкими, если LLM генерирует их в жесткую схему

### Property Graph

Это мир Neo4j-подобных систем, `GQL`, `SQL/PGQ`, `Cypher`-подходов.

Типовая модель:
- nodes
- relationships
- properties

Почему это важно:
- очень удобно для traversal
- отлично работает для neighborhood expansion и graph queries
- инженерно часто проще, чем RDF/OWL

Где болит:
- семантика слабее и менее формальна, чем в RDF/OWL
- правила и вывод часто уезжают в код или query logic

### JSON documents / typed records

Это не "классический KR formalism", но это очень сильный практический формат для LLM memory.

Почему это важно:
- легко эволюционирует
- легко валидируется
- хорошо сочетается с structured outputs
- удобно хранить provenance, qualifiers, time, confidence

Где болит:
- отношения и inference нужно проектировать явно
- если все хранить blob-ами, multi-hop reasoning и entity resolution быстро становятся грязными

## 1.2 Практический вывод из классического KR

Единого ответа нет.

Если цель такая:
- формальная семантика и ontology-heavy мир -> `RDF/OWL`
- rule-first reasoning -> `Datalog / logic`
- graph traversal и application engineering -> `property graph`
- прагматичная память для LLM-приложения -> `typed JSON objects + explicit links + optional graph projection`

Для LLM-систем обычно лучше стартовать с последнего варианта, но borrow ideas из triples, provenance и temporal modeling.

## 2. Что меняется, когда потребитель знаний - LLM

LLM memory - это не просто "база, по которой модель ищет".

Более точная модель такая:
- есть `working memory` внутри context window
- есть `long-term memory` вне контекста
- есть явная логика, которая решает:
  - что сохранять
  - что консолидировать
  - что считать устаревшим
  - как это потом вытаскивать

## 2.1 Типы памяти, которые реально нужны

Самая полезная taxonomy, которую подтверждают и research papers, и framework docs:

- `semantic memory` - факты и понятия
- `episodic memory` - события, диалоги, действия, tool traces
- `procedural memory` - инструкции, playbooks, recipes, successful patterns

Если хранить все как один одинаковый тип записи, recall быстро деградирует.

## 2.2 Почему "просто хранить историю чата" - слабая стратегия

Проблемы у raw history типовые:

- слишком длинно
- плохой temporal recall
- противоречия копятся
- важный факт тонет в шуме
- retrieval находит похожий текст, а не нужное evidence
- при обновлении состояния старая и новая версии живут рядом, и модель начинает гадать

## 2.3 Почему "просто embeddings" - тоже слабая стратегия

Dense retrieval полезен, но не самодостаточен.

Типовые провалы:
- промах по точным lexical anchors
- слабость на temporal disambiguation
- слабость на multi-hop entity relations
- плохое contradiction handling
- слабость на глобальных вопросах по корпусу
- возвращаются semantic neighbors, а не валидные доказательства

## 2.4 Почему "просто knowledge graph" - тоже не ответ

Graph-only подход тоже часто проигрывает, если использовать его догматично:

- дорого ingest-ить
- forcing everything into triples режет смысл
- notes, episodes, traces, procedures плохо ложатся в чистые triples
- строгая схема может сделать LLM extraction хрупким

Практический ответ почти везде один: `hybrid`.

## 3. Что показывают исследования и практические системы

## 3.1 CoALA

Главная идея:
- language agent лучше мыслить как cognitive architecture с модульной памятью, действиями и decision loop

Почему это важно:
- это хорошая mental model
- она вытаскивает разговор из уровня "ну давайте еще один retrieval хак"
- память становится subsystem, а не мусорным мешком

Практический вывод:
- память надо проектировать как набор типов и операций

Источник:
- [CoALA: Cognitive Architectures for Language Agents](https://arxiv.org/abs/2309.02427)

## 3.2 Survey papers по memory для LLM agents

Два полезных якоря:

- один survey просто широко картографирует поле
- второй делает более интересную вещь: раскладывает память на формы и операции

Самый полезный abstraction из второго:
- формы памяти: `parametric` и `contextual`
- операции: `consolidation`, `updating`, `indexing`, `forgetting`, `retrieval`, `condensation`

Почему это важно:
- сразу становится видно, что вопрос не сводится к "vector DB vs graph DB"
- реальная сложность живет в pipeline и операциях

Источники:
- [A Survey on the Memory Mechanism of Large Language Model based Agents](https://arxiv.org/abs/2404.13501)
- [Rethinking Memory in LLM based Agents: Representations, Operations, and Emerging Topics](https://arxiv.org/abs/2505.00675)

## 3.3 LongMemEval

Это один из самых полезных источников не потому, что там "еще один benchmark", а потому что там память разложена на pipeline:

- `indexing`
- `retrieval`
- `reading`

И оцениваются не только "похоже нашел или нет", а:
- information extraction
- multi-session reasoning
- temporal reasoning
- knowledge updates
- abstention

Два важных вывода:

- хорошая память - это не просто similarity search
- способность честно воздержаться важна не меньше, чем recall

Практический вывод:
- если система не тестируется на temporal questions, updates и abstention, то она неполноценна

Источник:
- [LongMemEval: Benchmarking Chat Assistants on Long-Term Interactive Memory](https://proceedings.iclr.cc/paper_files/paper/2025/file/d813d324dbf0598bbdc9c8e79740ed01-Paper-Conference.pdf)

## 3.4 Anthropic Contextual Retrieval

Это одна из самых практичных идей в retrieval-слое.

Идея:
- перед embedding chunk-а к нему добавляется маленький кусок контекста, сгенерированный из всего документа
- затем используются и contextual embeddings, и contextual BM25

Почему это важно:
- обычный chunking часто отрывает кусок текста от смысла
- локальный фрагмент без document context может плохо искаться

Практический вывод:
- plain chunking почти всегда слабее, чем document-aware chunk preprocessing
- hybrid retrieval должен быть default path, а не optional upgrade

Источник:
- [Anthropic: Contextual Retrieval](https://www.anthropic.com/engineering/contextual-retrieval)

## 3.5 GraphRAG

GraphRAG важен, но его очень легко начать применять не туда.

Где он реально силен:
- глобальные вопросы по большому корпусу
- themes / communities / patterns
- query-focused summarization
- multi-hop sensemaking

Где он не является автоматическим победителем:
- personalized long-term memory
- частые temporal updates по пользователю или проекту
- write-heavy conversational memory

Главная ценность GraphRAG:
- построить entity graph из текста
- построить community summaries
- отвечать на глобальные вопросы через structured summary composition

Практический вывод:
- GraphRAG отлично подходит для corpus-level reasoning
- но это не универсальная память агента

Источники:
- [Microsoft GraphRAG project](https://www.microsoft.com/en-us/research/project/graphrag/)
- [GraphRAG paper: From Local to Global](https://arxiv.org/abs/2404.16130)
- [Microsoft GraphRAG GitHub repository](https://github.com/microsoft/graphrag)

## 3.6 Zep / Graphiti / temporal knowledge graphs

Это один из самых сильных аргументов в пользу temporal memory.

Главные идеи:
- строить temporal knowledge graph из разговоров и данных
- хранить историю, а не только текущее состояние
- не перезаписывать факт вслепую, а закрывать его validity range
- генерировать memory context из графа и связанных фактов

Это бьет в очень реальную проблему:
- пользователь сказал что-то в понедельник
- уточнил или исправил в пятницу
- наивная память достает обе версии
- модель начинает гадать, что из этого "текущее"

Temporal graph memory вместо этого пытается хранить:
- что было истинно
- когда стало истинно
- когда перестало быть истинно

Практический вывод:
- если состояние меняется во времени, temporal validity нельзя оставлять "на потом"

Источники:
- [Zep paper: A Temporal Knowledge Graph Architecture for Agent Memory](https://arxiv.org/abs/2501.13956)
- [Zep concepts documentation](https://help.getzep.com/v2/concepts)
- [Graphiti GitHub repository](https://github.com/getzep/graphiti)

## 3.7 A-MEM

Для задачи "можем написать свое" это одна из самых интересных идей.

Главная мысль:
- memory можно хранить не только как chunks или triples, а как структурированные `notes`
- notes имеют атрибуты, теги, context, links
- новые notes могут изменять представление старых

Это важно потому, что реальная память агента редко выглядит как чистая онтология.
Скорее это смесь:
- Zettelkasten-like notes
- distilled facts
- evolving links
- incremental consolidation

Практический вывод:
- `memory note` выглядит очень сильной примитивой
- notes могут сосуществовать с claims, graph edges и raw episodes

Источник:
- [A-MEM: Agentic Memory for LLM Agents](https://arxiv.org/abs/2502.12110)

## 3.8 MemGPT

У MemGPT самое важное - не формат хранения, а operating model.

Ключевая идея:
- память надо делать tiered
- маленький working set остается в prompt
- остальное paging / swapping / recall по необходимости

Почему это важно:
- большие контексты не отменяют memory architecture
- latency и token cost никуда не деваются
- управление памятью - это control problem, а не только storage problem

Практический вывод:
- всегда надо различать:
  - active context
  - retrievable memory
  - archive / history

Источник:
- [MemGPT: Towards LLMs as Operating Systems](https://arxiv.org/abs/2310.08560)

## 3.9 Mem0

Mem0 для меня важен скорее как рыночный сигнал.

Что он показывает:
- индустрии нужен reusable memory layer
- людей волнуют token cost и latency
- full-context replay обычно проигрывает более умной дистилляции

Я бы не доверял вслепую любым вендорским цифрам, но направление полезное:
- extraction и consolidation важнее, чем тупое повторное скармливание истории целиком

Источники:
- [Mem0 paper](https://arxiv.org/abs/2504.19413)
- [Mem0 documentation](https://docs.mem0.ai/)
- [Mem0 GitHub repository](https://github.com/mem0ai/mem0)

## 3.10 LangGraph / LangChain memory docs

Это не теория, а framework docs, но они полезны тем, что честно проговаривают вещи, которые многие прячут:

- long-term memory - это не то же самое, что thread-scoped state
- one-size-fits-all решения нет
- semantic / episodic / procedural memory - полезные distinctions
- memory можно писать на hot path или background job-ом
- долгоживущая память может быть просто набором JSON documents в namespaces

Почему это полезно:
- подтверждает, что pragmatic memory store в виде typed documents - нормальный первый шаг

Источники:
- [LangGraph memory overview](https://docs.langchain.com/oss/javascript/langgraph/memory)
- [LangChain long-term memory docs](https://docs.langchain.com/oss/python/langchain/long-term-memory)

## 3.11 OpenAI retrieval / file search

OpenAI-овские retrieval docs полезны не как полная memory architecture, а как reference point для hosted retrieval tools.

Что из этого стоит забрать:
- `file search` - это retrieval capability, а не whole memory model
- metadata filters важны
- количество результатов - это tradeoff качества, latency и noise
- citations и surfaced evidence важны

Практический вывод:
- hosted retrieval может закрыть документный поиск
- но он не заменяет temporal memory, semantic consolidation и procedural memory

Источники:
- [OpenAI File Search guide](https://developers.openai.com/api/docs/guides/tools-file-search)
- [OpenAI Knowledge Retrieval blueprint](https://openai.com/solutions/blueprints/knowledge-retrieval/)

## 4. Что кажется истинным после просмотра всех источников

Это, по-моему, самые устойчивые выводы.

## 4.1 Память требует явных типов

Минимум:
- working memory
- semantic memory
- episodic memory
- procedural memory

Если все хранится одинаково, retrieval и update logic быстро ломаются.

## 4.2 Память требует явных операций

Минимум:
- capture
- extract
- consolidate
- update
- invalidate
- index
- retrieve
- condense
- forget или de-prioritize

Это важнее, чем конкретная БД.

## 4.3 Время и provenance - first-class

Если система не умеет ответить:
- откуда взялся этот факт?
- когда он стал валиден?
- когда он был заменен?

то рано или поздно она начнет "вспоминать" устаревшее или ложное состояние.

## 4.4 Retrieval должен быть hybrid

Базовый стек retrieval должен включать:
- dense retrieval
- lexical retrieval
- metadata filtering
- temporal filtering
- optional graph expansion
- reranking

Single-channel retrieval почти всегда слаб.

## 4.5 Consolidation важнее, чем branding storage-слоя

Memory systems чаще всего ломаются из-за:
- плохого extraction
- плохого deduplication
- отсутствия contradiction handling
- отсутствия salience policy
- отсутствия evidence linking

а не из-за "не той vector DB".

## 5. Что я бы рекомендовал, если писать систему самим

## 5.1 Начинать надо не с базы, а с Memory IR

Базовые типы, которые я бы ввел:

- `Episode`
  - иммутабельное сырое событие
  - chat turn, tool output, imported note, external record, observation, action result
- `Entity`
  - каноническая сущность с aliases
- `Claim`
  - нормализованный факт
- `Note`
  - richer synthesized memory note
- `Procedure`
  - инструкция, рецепт, playbook, reusable trace
- `EvidenceLink`
  - ссылка от memory object к raw evidence
- `ValidityRange`
  - `valid_from`, `valid_to`

## 5.2 Как должен выглядеть хороший memory fact

Пример:

```json
{
  "id": "claim_123",
  "type": "claim",
  "subject": "service.auth",
  "predicate": "uses_provider",
  "object": "oauth2",
  "qualifiers": {
    "environment": "prod"
  },
  "valid_from": "2026-04-01T10:00:00Z",
  "valid_to": null,
  "confidence": 0.87,
  "source": {
    "episode_id": "msg_998",
    "origin": "chat",
    "span": [128, 214]
  },
  "aliases": [
    "authentication service",
    "auth backend"
  ]
}
```

Почему это хорошая форма:
- достаточно похоже на triple, чтобы строить graph reasoning
- достаточно похоже на document, чтобы было удобно хранить и эволюционировать
- умеет provenance
- умеет temporal validity

## 5.3 Где это хранить на первом шаге

Я бы стартовал с Postgres.

Не потому что это "идеологически правильно", а потому что это лучший control point.

Предлагаемый набор таблиц:
- `episodes`
- `entities`
- `claims`
- `notes`
- `procedures`
- `evidence_links`
- `edges`

Что желательно иметь:
- JSONB
- pgvector
- full-text / BM25-like retrieval
- B-tree indexes по времени, namespaces, entity IDs

Почему это хороший старт:
- легко мигрировать
- легко дебажить
- легко делать hybrid retrieval
- не заставляет слишком рано коммититься в graph DB
- можно потом строить projections хоть в graph, хоть в RDF-подобный слой

## 5.4 Write path

Я бы строил pipeline так:

1. Capture raw episode.
2. Extract candidate entities, claims, notes.
3. Resolve entities against existing memory.
4. Deduplicate и проставить evidence links.
5. Detect contradictions.
6. Если новый факт заменяет старый, закрыть старому `valid_to`, а не удалять его.
7. Обновить или создать memory notes.
8. Проиндексировать все, что нужно для recall.

## 5.5 Recall path

Pipeline recall я бы делал так:

1. Классифицировать запрос:
   - semantic?
   - episodic?
   - procedural?
   - temporal?
   - mixed?
2. Запустить retrieval:
   - lexical
   - dense
   - metadata / namespace filters
   - time filters
3. Расширить результат:
   - neighbors по entities
   - связанные notes
   - evidence links
4. Реранжировать:
   - exact entity overlap
   - temporal fit
   - confidence
   - provenance quality
5. Собрать evidence pack.
6. Просить модель отвечать из evidence pack с возможностью abstain, если evidence слабый.

## 5.6 Почему `Note` - очень сильная примитива

Я бы не хранил только facts.
Я бы обязательно хранил еще и notes.

Почему:
- facts точнее, но хрупче
- notes лучше сохраняют локальный смысл
- LLM обычно лучше пишет хорошие notes, чем идеальные triples
- notes потом можно развернуть в claims, summaries, graph edges, few-shot traces

Что в note полезно хранить:
- summary
- entities
- tags
- salient facts
- temporal hints
- confidence
- evidence pointers
- related notes

## 5.7 Procedural memory нельзя оставлять на потом

Многие системы памяти слишком зациклены на "фактах о пользователе" и недостраивают "как делать вещи".

Procedural memory должна хранить:
- successful workflows
- fixes
- troubleshooting playbooks
- prompt fragments
- action sequences
- few-shot traces

Это особенно важно, если агент должен со временем становиться полезнее, а не просто помнить предпочтения.

## 6. Что я бы не делал

- Не делал бы embeddings source of truth.
- Не пытался бы в первый же день загнать все в triples.
- Не перезаписывал бы факты без temporal closure.
- Не давал бы модели отвечать прямо из nearest neighbors без evidence assembly.
- Не надеялся бы, что long context сам заменит memory architecture.
- Не строил бы тяжелую ontology заранее, пока не понятен реальный workload.

## 7. Если формулировать целевое направление очень коротко

Если требование звучит так:

"LLM должна потом вспоминать нужное, а не просто находить похожий текст"

то система должна оптимизироваться под:

- salience-aware writing
- explicit claims и notes
- provenance
- temporal validity
- hybrid recall
- abstention

Архитектурно это выглядит так:

- event-sourced memory log
- semantic distillation layer
- optional temporal graph layer
- hybrid retrieval
- evidence-based answer generation

В одну строку:

Надо строить memory system, а не document dump.

## 8. Что имеет смысл поручить следующему агенту

Самое полезное продолжение этой работы:

- спроектировать `Memory IR`
- определить операции `extract / update / consolidate / recall`
- нарисовать первую схему `Postgres + pgvector`
- придумать evaluation tasks для:
  - recall accuracy
  - temporal correctness
  - update correctness
  - contradiction handling
  - abstention

## 9. Что именно я гуглил по темам

Ниже не сырой лог всех запросов, а нормализованный search trail по темам.

### Классическое knowledge representation

- W3C RDF 1.1
- RDF concepts
- OWL 2 primer
- SHACL
- SKOS
- PROV-O
- JSON-LD
- Common Logic ISO
- predicate logic
- description logic
- ontology development
- property graph
- openCypher
- GQL
- SQL/PGQ

### Память и knowledge для LLM agents

- long-term memory for LLM agents
- semantic / episodic / procedural memory
- memory benchmarks for chat assistants
- indexing / retrieval / reading
- temporal reasoning and knowledge updates
- surveys on agent memory

### Retrieval / graph / productized memory

- GraphRAG
- temporal knowledge graph memory
- Zep / Graphiti
- MemGPT
- Mem0
- LangGraph memory
- OpenAI retrieval / file search
- Anthropic contextual retrieval

## 10. Annotated bibliography со ссылками

Это handoff-friendly список: зачем источник был нужен и что именно он дал.

## 10.1 База: KR, ontology, standards

- [MIT: What Is a Knowledge Representation?](https://groups.csail.mit.edu/medg/ftp/psz/k-rep.html)
  - Полезен как conceptual grounding: что вообще считать knowledge representation.

- [Stanford: Ontology Development 101](https://protege.stanford.edu/publications/ontology_development/ontology101.pdf)
  - Хороший практический вход в ontology engineering и controlled vocabularies.

- [W3C RDF 1.1 Concepts](https://www.w3.org/TR/rdf11-concepts/)
  - Канонический reference для RDF как графовой модели утверждений.

- [W3C OWL 2 Primer](https://www.w3.org/TR/owl2-primer/)
  - Нужен, чтобы не путать triples с ontology semantics.

- [W3C SHACL](https://www.w3.org/TR/shacl/)
  - Полезен, если когда-нибудь понадобится валидация graph-shaped данных.

- [W3C SKOS Reference](https://www.w3.org/TR/skos-reference/)
  - Полезен для тезаурусов, concept hierarchies, taxonomies и labels.

- [W3C PROV-O](https://www.w3.org/TR/prov-o/)
  - Очень релевантен теме provenance, которая критична для LLM memory.

- [W3C JSON-LD 1.1](https://www.w3.org/TR/json-ld11/)
  - Показывает, как linked data может жить внутри JSON syntax.

- [ISO Common Logic 24707:2018](https://www.iso.org/standard/66249.html)
  - Полезен как "самый формальный край" спектра.

- [GQL standard site](https://www.gqlstandards.org/)
  - Нормальная точка входа в стандартизируемый мир property graphs.

- [ISO draft page for GQL / ISO 39075](https://www.iso.org/standard/89917.html)
  - Подтверждает, что GQL - это не только маркетинг, а реальная стандартизация.

- [ISO SQL/PGQ page](https://www.iso.org/es/contents/data/standard/07/94/79473.html)
  - Важно, если graph queries хочется встраивать в SQL ecosystem.

## 10.2 Теория и обзор memory для LLM agents

- [CoALA: Cognitive Architectures for Language Agents](https://arxiv.org/abs/2309.02427)
  - Дает сильную conceptual model для памяти как модуля агента.

- [A Survey on the Memory Mechanism of Large Language Model based Agents](https://arxiv.org/abs/2404.13501)
  - Широкий обзор memory mechanisms и их ограничений.

- [Rethinking Memory in LLM based Agents: Representations, Operations, and Emerging Topics](https://arxiv.org/abs/2505.00675)
  - Один из самых полезных источников, потому что раскладывает память по operations.

- [LongMemEval: Benchmarking Chat Assistants on Long-Term Interactive Memory](https://proceedings.iclr.cc/paper_files/paper/2025/file/d813d324dbf0598bbdc9c8e79740ed01-Paper-Conference.pdf)
  - Важен из-за decomposition на indexing / retrieval / reading и из-за temporal/update/abstention измерений.

## 10.3 Retrieval и graph-enhanced память

- [Anthropic: Contextual Retrieval](https://www.anthropic.com/engineering/contextual-retrieval)
  - Один из самых полезных инженерных текстов про то, как улучшить качество chunk retrieval.

- [Microsoft GraphRAG project](https://www.microsoft.com/en-us/research/project/graphrag/)
  - Official hub для GraphRAG.

- [GraphRAG paper: From Local to Global](https://arxiv.org/abs/2404.16130)
  - Хорошо показывает, где именно GraphRAG выигрывает.

- [Microsoft GraphRAG GitHub repository](https://github.com/microsoft/graphrag)
  - Нужен, если следующему агенту понадобится implementation detail.

- [Zep paper: A Temporal Knowledge Graph Architecture for Agent Memory](https://arxiv.org/abs/2501.13956)
  - Сильный аргумент в пользу temporal knowledge representation.

- [Zep concepts documentation](https://help.getzep.com/v2/concepts)
  - Практическое объяснение temporal graph memory и invalidation semantics.

- [Graphiti GitHub repository](https://github.com/getzep/graphiti)
  - Open-source reference implementation temporal memory graph approach.

- [A-MEM: Agentic Memory for LLM Agents](https://arxiv.org/abs/2502.12110)
  - Особенно интересен идеей notes как основной memory primitive.

## 10.4 Operating model / products / framework pragmatics

- [MemGPT: Towards LLMs as Operating Systems](https://arxiv.org/abs/2310.08560)
  - Важен tiered memory подходом.

- [Mem0 paper](https://arxiv.org/abs/2504.19413)
  - Полезен как production-memory signal, но цифры надо читать критически.

- [Mem0 documentation](https://docs.mem0.ai/)
  - Дает представление, как коммерческий memory layer упаковывает задачу.

- [Mem0 GitHub repository](https://github.com/mem0ai/mem0)
  - Полезно для code-level просмотра.

- [LangGraph memory overview](https://docs.langchain.com/oss/javascript/langgraph/memory)
  - Хорошо фиксирует semantic / episodic / procedural split и способы записи memory.

- [LangChain long-term memory docs](https://docs.langchain.com/oss/python/langchain/long-term-memory)
  - Подтверждает, что JSON-document memory stores - нормальный pragmatic baseline.

- [OpenAI File Search guide](https://developers.openai.com/api/docs/guides/tools-file-search)
  - Полезен как reference по hosted retrieval, metadata filters и evidence surfacing.

- [OpenAI Knowledge Retrieval blueprint](https://openai.com/solutions/blueprints/knowledge-retrieval/)
  - Хорош как high-level пример grounded retrieval assistant.

## 11. Bottom line

Если все это сжать до одного принципа, то он такой:

Не надо спрашивать:

"какой один-единственный язык выбрать для записи знаний?"

Надо спрашивать:

- какие типы памяти нужны?
- какие операции над памятью нужны?
- какая temporal и provenance semantics нужна?
- как сделать так, чтобы модель доставала правильное evidence, а не просто semantic neighbor?

Для LLM-oriented системы самый практичный ответ сейчас почти всегда такой:

- typed memory objects
- explicit claims и notes
- provenance
- temporal validity
- hybrid retrieval
- evidence-grounded answer generation

Остальное - уже implementation detail.
