# Claude Code Streaming Integration

## Working Configuration

For interactive streaming applications, use:
```kotlin
val command = listOf(
    "bash", "-c", 
    "claude --output-format stream-json --input-format stream-json --verbose --resume $sessionId"
)
```

## Input Format (`--input-format stream-json`)

When using streaming input, messages must be JSON formatted and sent to stdin:

```json
{
  "type": "user",
  "message": {
    "role": "user", 
    "content": "your message here"
  }
}
```

### Sending Messages
```kotlin
val jsonMessage = objectMapper.writeValueAsString(mapOf(
    "type" to "user",
    "message" to mapOf(
        "role" to "user",
        "content" to userInput
    )
))
stdinWriter.write("$jsonMessage\n")
stdinWriter.flush()
```

## Output Format (`--output-format stream-json`)

Claude Code outputs multiple JSON lines for each interaction:

### 1. System Init Message
```json
{
  "type": "system",
  "subtype": "init", 
  "session_id": "abc123...",
  "tools": ["Task", "Bash", "Grep", ...],
  "model": "claude-opus-4-20250514"
}
```

### 2. Assistant Response
```json
{
  "type": "assistant",
  "message": {
    "id": "msg_01...",
    "content": [{"type": "text", "text": "Response text"}],
    "usage": {"input_tokens": 4, "output_tokens": 10}
  },
  "session_id": "abc123..."
}
```

### 3. Result Summary
```json
{
  "type": "result",
  "subtype": "success",
  "is_error": false,
  "session_id": "abc123...",
  "total_cost_usd": 0.026,
  "duration_ms": 2930,
  "num_turns": 7
}
```

## Process Management

### Long-Running Process
- Keep the Claude Code process alive between messages
- Send JSON to stdin for each user message
- Read stdout continuously in a background thread/coroutine

### Error Handling
- Monitor process status
- Handle disconnections gracefully  
- Implement restart capability
- Parse each JSON line separately (not as single JSON)

## Implementation Example

See `ClaudeCodeStreamingWrapper.kt` for complete implementation with:
- Long-running process management
- JSON input/output handling
- Kotlin Flow for real-time UI updates
- Proper error handling and restart logic