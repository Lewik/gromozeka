# Memory E2E Quality Audit

Дата: 2026-05-08

Источник: свежий полный replay-run `MemoryRealModelE2eTest`.

Artifacts:

`presentation/build/test-artifacts/memory-e2e/MemoryRealModelE2eTest/2026-05-08T17-17-28.067798Z-gromozeka_memory_e2e_019e0898_2081_7735_9246_fb1d5af346b2`

## Короткий вывод

Память сейчас выглядит не игрушечной: write, recall, selector, task lifecycle, updates/supersede, forget, profile projection, repair и maintenance в replay-прогоне проходят согласованно.

Но это еще не значит, что система "реально хорошо работает" во всех живых диалогах. Тестовая сетка уже полезная, но часть зеленых кейсов пока проверяет результат сильнее, чем внутренний путь памяти. Следующий правильный шаг - не оптимизация стоимости, а усиление quality gates вокруг retrieval/source safety/maintenance.

## Что проверено

- Полный replay-run: 51/51 case `PASS`.
- База перед run очищалась, после run сохранена для анализа.
- Все report-файлы созданы.
- `SELECT CLAIM` встречается в 28 report-файлах.
- `SELECT SOURCE` встречается в 4 report-файлах.
- `SELECT NOTE` встречается в 3 report-файлах.
- `SELECT TASK` встречается в 5 report-файлах.
- `sourceSafety.suppressedSources` и `sourceSafety.restoredTypedHits` в этом run нигде не стали ненулевыми.

## Что выглядит хорошо

- Current-value updates работают: свежие active claims выигрывают у старых значений в `knowledge-update-latest-wins`, `project-current-value-replaces-old-value-recall`, `active-claim-overrides-old-source`, `used-to-current-preference`.
- Source-only поведение работает по назначению: uncertain/source provenance кейсы не превращаются в claims, но могут быть вспомнены как raw source, например `multilingual-source-bridge` и `uncertain-claim-confidence`.
- Task path выглядит живым: `INSERT`, `NOOP`, `UPDATE`, `CLOSE`, `CANCEL` проходят и потом нормально вспоминаются как task memory.
- Repair/maintenance в целом срабатывают: duplicate claims/notes/tasks/episodes схлопываются, conflicting claims supersede старое, profile drift обновляет projection.
- Selector чаще выбирает typed memory, а source берет только когда это оправдано: exact quote, uncertain raw observation или rationale provenance.
- Profile projection не подменяет active typed memory: в factual recall selector предпочитает active claims и reject-ит profile как projection cache.

## Подозрительные места

### 1. Source safety фактически не покрыт

В свежем run нет ни одного ненулевого `suppressedSources` или `restoredTypedHits`.

Это не обязательно баг: selector может просто не пропускать опасные sources. Но если у нас есть отдельный механизм source safety, текущий набор тестов почти не доказывает, что он работает. Нужен отдельный кейс, где stale/raw source реально попадает в candidates рядом с active typed claim, а source safety обязан его подавить.

### 2. Repair duplicate traces выглядят двусмысленно

В maintenance trace для duplicate repair встречается форма вроде `ACTIVE:archivedAt=...`.

В final snapshot остается только surviving active object, то есть на уровне итогового поиска поведение похоже правильное. Но trace/readability плохие: объект выглядит одновременно active и archived. Надо проверить, это только renderer или реальный lifecycle-инвариант, и добавить assertion: archived/superseded item не должен выглядеть как active selectable memory.

### 3. Не все зеленые кейсы одинаково строгие

Мы усилили несколько важных expectations, но часть cases все еще в основном проверяет answer/snapshot, а не selector decisions и injected prompt.

Наиболее полезно усилить:

- forget flows: `explicit-forget-memory`, `forget-specificity-keeps-related-project-fact`;
- maintenance/repair flows: duplicate repair и profile drift;
- source-only/rationale flows: `note-path-rationale`, `preference-dedup-evidence`;
- entity disambiguation: `same-name-project-person-disambiguation`, `entity-scoped-project-recall`;
- source safety special case, которого сейчас по сути нет.

### 4. Reports пока слабоваты по стоимости

Reports хорошо показывают memory path, но не дают нормальной per-case картины по LLM calls/tokens.

Для разработки это не блокер, но дальше будет полезно видеть:

- сколько LLM-вызовов ушло на seed/recall/maintenance;
- input/output tokens по case;
- какие stages самые дорогие;
- сколько было cassette hits/misses.

## Что делать следующим

1. Проверить и поправить lifecycle/trace-инвариант вокруг `ACTIVE:archivedAt=...`.
2. Добавить source-safety E2E case, который реально заставляет `suppressedSources` стать ненулевым.
3. Усилить expectations у forget/repair/entity/source-only cases так же, как уже усилены 5 ключевых кейсов.
4. Добавить per-case LLM call/token summary в e2e reports.
5. Потом уже думать про оптимизацию количества LLM-вызовов.

## Update: закрыто

- `ACTIVE:archivedAt=...` оказался проблемой report-rendering, а не selectable lifecycle: обычный snapshot/search уже отсекает archived records. Renderer теперь показывает archived materialized records как `ARCHIVED(status=...,archivedAt=...)`, а repair tests проверяют, что archived loser не возвращается обычным snapshot/search.
- Добавлен `source-safety-suppresses-replaced-source`: real-model e2e доводит read path до `suppressedSources=1`. В этом сценарии `restoredTypedHits=0` корректен, потому что selector уже выбрал active claim; отдельный application-level тест покрывает ветку, где active claim надо восстановить после подавления stale source.

## Рабочий verdict

Сейчас система не выглядит сломанной. Она уже проходит широкий набор memory сценариев и по reports ведет себя в целом разумно.

Главный риск не в том, что memory pipeline не работает, а в том, что часть важных гарантий пока не закреплена достаточно строгими тестами. Поэтому следующий этап - укреплять e2e expectations и закрыть source-safety/lifecycle audit holes.
