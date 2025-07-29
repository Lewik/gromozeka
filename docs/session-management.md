# Claude Code Session Management

## Key Discovery: `--resume` Creates New Sessions

Contrary to intuition, when using `--resume <session-id>`, Claude Code:
1. Creates a **new** session file with a new UUID
2. Copies the entire conversation history from the old session  
3. Adds a `summary` entry linking to the old session
4. The original session ID is NOT continued

## How Sessions Are Linked

### Summary Object
The new session file starts with a summary object:
```json
{
  "type": "summary",
  "summary": "Minimal Chat Exchange with Numbers and Greeting",
  "leafUuid": "d1a909e9-cefd-4ee3-951d-3aca8c3e20f8"
}
```

### Key Field: `leafUuid`
- Points to the last message UUID from the previous session
- Establishes the parent-child relationship between sessions
- Allows Claude Code to understand conversation continuity

## Version Behavior

Different Claude Code versions maintain the same behavior:
- Version 1.0.61 → 1.0.62 transition tested
- Both versions create new sessions on `--resume`
- Version info is preserved in copied messages

## CLI Behavior

### Interactive Mode
```bash
claude --resume [session-id]
# OR
claude --continue  # Uses most recent session
```

### Non-Interactive Mode  
```bash
claude --resume [session-id] --print "message"
# Creates new session, preserves context
```

## Practical Implications

1. **Session IDs are not stable** - they change on every resume
2. **Context is preserved** - full conversation history is maintained
3. **Multiple session files** accumulate for the same logical conversation
4. **CLI shows unified view** - displays as single conversation despite multiple files

## Best Practices

1. **Don't hardcode session IDs** - they will become invalid
2. **Use `--continue`** for simpler session management when possible
3. **Track session IDs dynamically** from Claude Code responses
4. **Load from latest session file** when initializing UI