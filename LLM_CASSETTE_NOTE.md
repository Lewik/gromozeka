# LLM Cassette Note

LLM cassettes: record/replay слой для тестов, которые реально вызывают LLM.

Идея простая: первый live-прогон делает настоящий запрос к модели и сохраняет полный request/response на диск. Повторный прогон с тем же логическим запросом не идет в LLM, а воспроизводит сохраненный ответ. Это нужно, чтобы дорогие и медленные memory E2E тесты можно было гонять быстро, детерминированно и без случайных изменений ответа модели.

## Где стоит слой

Слой стоит не на уровне HTTP и не на уровне prompt text, а на уровне доменного AI runtime:

- `AiRuntimeProvider` оборачивается в test profile `e2e`.
- Любой `AiRuntime.call(...)` и `AiRuntime.stream(...)` проходит через cassette proxy.
- Ключ cassette строится из canonical JSON полного `AiRuntimeRequest`, provider, model и operation.
- Поэтому в fingerprint попадают не только текстовые сообщения, но и structured response schema, tools, options, instructions и прочие runtime-параметры.

Главные файлы:

- `presentation/src/jvmTest/kotlin/com/gromozeka/presentation/testsupport/llm/AiRuntimeCassetteProxy.kt`
- `presentation/src/jvmTest/resources/llm-cassettes/`

## Режимы

Режим задается системным свойством:

```bash
-Dgromozeka.llm.cassette.mode=...
```

Поддерживаются:

- `off` - cassette layer выключен.
- `record-missing` - если cassette есть, replay; если нет, настоящий LLM call и запись.
- `replay-only` - только replay, при miss падать. Это режим для CI/проверки кеша.
- `refresh` - всегда делать настоящий LLM call и перезаписывать cassette.

Для `gromozeka.memory.e2e=true` дефолтный режим - `replay-only`, чтобы обычный проверочный прогон случайно не ушел в live LLM. Для записи новых cassette надо явно указать `record-missing` или `refresh`.

Директория задается так:

```bash
-Dgromozeka.llm.cassette.dir=/path/to/llm-cassettes
```

По умолчанию для E2E/test mode используется:

```text
presentation/src/jvmTest/resources/llm-cassettes
```

В обычном runtime default должен быть в Gromozeka home:

```text
<GROMOZEKA_HOME>/llm-cassettes
```

## Нормализация динамики

Чтобы одинаковый логический запрос попадал в ту же cassette, proxy нормализует volatile поля:

- runtime ids и namespace-like ids;
- project/chat/entity/claim/task/source ids;
- provider response/message/tool-call metadata;
- некоторые runtime timestamps;
- временные поля внутри JSON structured outputs, если они выглядят как даты текущего запуска, а не как смысловые даты пользователя.

При replay proxy частично восстанавливает значения под текущий запуск, чтобы downstream-код не ломался на старых id/timestamp.

Важная граница: мы не пытаемся "понимать" ответ модели и не исправляем semantic content. Cassette хранит именно ответ модели после стабильной технической нормализации.
