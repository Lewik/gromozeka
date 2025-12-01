# Claude Code CLI: Поддерживаемые форматы входных сообщений

## Краткий вывод

**Claude Code CLI принимает ОБА формата:**
1. ✅ **Anthropic API формат** (простой) - используется Cline и ClaudeCodeApi.java
2. ✅ **SDK формат** (с оберткой) - используется Python/TypeScript SDK

## Формат 1: Anthropic API (простой)

```json
[
  {
    "role": "user",
    "content": [{"type": "text", "text": "..."}]
  },
  {
    "role": "assistant",
    "content": [{"type": "text", "text": "..."}]
  }
]
```

**Используют:**
- Cline: `/Users/lewik/code/gromozeka/dev/.sources/cline/src/integrations/claude-code/run.ts:237`
  ```typescript
  claudeCodeProcess.stdin.write(JSON.stringify(messages))
  // messages: Anthropic.Messages.MessageParam[]
  ```

- ClaudeCodeApi.java: `infrastructure-ai/src/jvmMain/java/org/springframework/ai/claudecode/api/ClaudeCodeApi.java:270-335`
  ```java
  map.put("role", message.role().name().toLowerCase());
  map.put("content", contentList);
  ```

**Проверка работы:**
```bash
echo '[{"role":"user","content":"OK"}]' | \
  claude -p --verbose --output-format stream-json "respond with OK"
```

**Результат**: ✅ Работает (проверено 2025-12-01)

## Формат 2: SDK формат (с оберткой)

```json
{
  "type": "user",
  "message": {
    "role": "user",
    "content": [{"type": "text", "text": "..."}]
  },
  "parent_tool_use_id": null,
  "session_id": "default"
}
```

**Используют:**
- Python SDK: `.sources/claude-agent-sdk-python/src/claude_agent_sdk/client.py:195-200`
  ```python
  message = {
      "type": "user",
      "message": {"role": "user", "content": prompt},
      "parent_tool_use_id": None,
      "session_id": session_id,
  }
  await self._transport.write(json.dumps(message) + "\n")
  ```

- TypeScript SDK: `.sources/package/sdk.d.ts:311-332`
  ```typescript
  type SDKUserMessage = {
      type: 'user';
      message: APIUserMessage;
      parent_tool_use_id: string | null;
      session_id: string;
  };
  ```

**Проверка работы:**
```bash
echo '{"type":"user","message":{"role":"user","content":"test"},"parent_tool_use_id":null,"session_id":"default"}' | \
  claude -p --verbose --input-format stream-json --output-format stream-json "respond with OK"
```

**Результат**: ✅ Работает (проверено 2025-12-01)

## Когда какой формат использовать

### Anthropic API формат
- ✅ Простой JSON массив сообщений
- ✅ Работает с обычным `-p` флагом
- ✅ Совместим с Anthropic Messages API
- ✅ Используется Cline (проверенное решение)
- **Рекомендуется для простых случаев**

### SDK формат
- ✅ Streaming input (`--input-format stream-json`)
- ✅ Поддержка `session_id` и `parent_tool_use_id`
- ✅ Используется официальными SDK
- **Нужен для advanced use cases (streaming, multi-session)**

## Что используем мы

**ClaudeCodeApi.java использует Anthropic API формат** - это правильно:
1. Простой и понятный
2. Совместим с существующим `AnthropicApi.AnthropicMessage`
3. Проверен в production (Cline)
4. Не требует дополнительной обертки

## Места для проверки

### Текущая реализация
- **ClaudeCodeApi.java**: `infrastructure-ai/src/jvmMain/java/org/springframework/ai/claudecode/api/ClaudeCodeApi.java`
  - Строки 270-275: `writeMessagesToStdin()` - отправка сообщений
  - Строки 325-335: `convertMessageToMap()` - формирование Anthropic API формата

### Cline (reference implementation)
- **run.ts**: `.sources/cline/src/integrations/claude-code/run.ts:237`
  - `JSON.stringify(messages)` где `messages: Anthropic.Messages.MessageParam[]`

### Python SDK
- **client.py**: `.sources/claude-agent-sdk-python/src/claude_agent_sdk/client.py:195-200`
  - Формирование SDK формата с оберткой

### TypeScript SDK
- **sdk.d.ts**: `.sources/package/sdk.d.ts:311-332`
  - Определение типа `SDKUserMessage`

### Claude Code CLI docs
```bash
claude --help | grep -A 5 "input-format"
```

**Флаг `--input-format`**:
- `text` (default) - простой текст или Anthropic API JSON
- `stream-json` - требует SDK формат с streaming

## Тесты для проверки

### Тест 1: Anthropic API формат
```bash
echo '[{"role":"user","content":"test"}]' | \
  claude -p --verbose --output-format stream-json "echo OK" 2>&1 | \
  grep '"type":"assistant"'
```

**Ожидается**: Сообщение от assistant с "OK"

### Тест 2: SDK формат
```bash
echo '{"type":"user","message":{"role":"user","content":"test"},"parent_tool_use_id":null,"session_id":"test"}' | \
  claude -p --verbose --input-format stream-json --output-format stream-json "echo OK" 2>&1 | \
  grep '"type":"assistant"'
```

**Ожидается**: Сообщение от assistant с "OK"

### Тест 3: Множественные сообщения (Anthropic API)
```bash
echo '[{"role":"user","content":"1"},{"role":"assistant","content":"ok"},{"role":"user","content":"2"}]' | \
  claude -p --verbose --output-format stream-json "respond with DONE" 2>&1 | \
  grep '"result"'
```

**Ожидается**: Result message с "DONE"

## История изменений

- **2025-12-01**: Первоначальное исследование
  - Проверены оба формата на работоспособность
  - Подтверждено что ClaudeCodeApi.java использует правильный формат
  - Найдено что оба формата поддерживаются Claude Code CLI
