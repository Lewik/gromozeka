# Claude SDK: Управление историей сообщений

## Краткий ответ
**ДА, можно** передавать всю историю сообщений в Claude Code через SDK и контролировать сессию самостоятельно.

## Как это работает

### Python SDK

**One-shot с историей:**
```python
from claude_agent_sdk import query, ClaudeAgentOptions

async def my_history():
    yield {"type": "user", "message": {"role": "user", "content": "Hello"}}
    yield {"type": "user", "message": {"role": "user", "content": "What is 2+2?"}}

async for message in query(prompt=my_history()):
    print(message)
```

**Streaming с историей:**
```python
from claude_agent_sdk import ClaudeSDKClient

async def my_history():
    yield {"type": "user", "message": {"role": "user", "content": "..."}, ...}

async with ClaudeSDKClient() as client:
    await client.query(my_history())
    async for msg in client.receive_response():
        print(msg)
```

## Где искать доказательства

### Основные файлы
1. **Сигнатура `query()`**: `.sources/claude-agent-sdk-python/src/claude_agent_sdk/query.py:12-14`
   - Параметр: `prompt: str | AsyncIterable[dict[str, Any]]`
   - Документация: строки 46-54, 88-96

2. **Пример с историей**: `.sources/claude-agent-sdk-python/examples/streaming_mode.py:248-293`
   - Функция `example_async_iterable_prompt()`
   - Демонстрирует передачу нескольких сообщений через generator

3. **ClaudeSDKClient.query()**: `.sources/claude-agent-sdk-python/src/claude_agent_sdk/client.py:180-208`
   - Поддерживает как `str`, так и `AsyncIterable[dict]`

4. **Формат сообщения**:
```python
{
    "type": "user",
    "message": {"role": "user", "content": "..."},
    "parent_tool_use_id": None,
    "session_id": "your-session-id"
}
```

### Типы
- **ClaudeAgentOptions**: `.sources/claude-agent-sdk-python/src/claude_agent_sdk/types.py:525-579`
  - `continue_conversation: bool = False` (строка 533)
  - `resume: str | None = None` (строка 534)
  - `fork_session: bool = False` (строка 567)

## Ключевые моменты

1. **One-shot режим** (`query()`) - запускает `claude`, отправляет историю, получает ответ, завершается
2. **Streaming режим** (`ClaudeSDKClient`) - держит соединение открытым, можно отправлять сообщения динамически
3. **Контроль сессии** - через `session_id` в каждом сообщении
4. **История** - передается как async generator/iterable

## TypeScript SDK

**NPM пакет**: `@anthropic-ai/claude-agent-sdk@0.1.55`
**Локальная копия**: `.sources/package/` (распакованный npm пакет)

### Сигнатура query()
```typescript
export declare function query(_params: {
    prompt: string | AsyncIterable<SDKUserMessage>;  // ← Поддерживает AsyncIterable!
    options?: Options;
}): Query;
```

### Формат SDKUserMessage
```typescript
type SDKUserMessage = {
    type: 'user';
    message: APIUserMessage;  // from @anthropic-ai/sdk
    parent_tool_use_id: string | null;
    isSynthetic?: boolean;
    tool_use_result?: unknown;
    uuid?: UUID;
    session_id: string;
};
```

**Детали**: См. `claude-agent-sdk-typescript-api.md` для полного API reference и Kotlin/JS mappings
