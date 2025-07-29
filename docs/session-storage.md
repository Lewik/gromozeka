# Claude Code Session Storage

## Storage Location

Claude Code stores all session data locally in:
```
~/.claude/projects/[encoded-project-path]/
```

## Path Encoding Rules

Project paths are encoded by replacing `/` with `-` while preserving case:

| Original Path | Encoded Path |
|--------------|--------------|
| `/home/user/MyProject` | `-home-user-MyProject` |

## File Format

### JSONL Structure
- Files use `.jsonl` format (JSON Lines - https://jsonlines.org/)
- Each line is a separate JSON object
- No commas between lines
- Can be read line-by-line

### Message Structure
Each line contains a message object with:
- `uuid`: Unique identifier for this message
- `parentUuid`: Links to the previous message in the conversation
- `sessionId`: The session this message belongs to
- `type`: Message type (user, assistant, summary)
- `version`: Claude Code version that created this entry
- `timestamp`: When the message was created

### Example Entry
```json
{
  "parentUuid": "f8b14607-2f5e-4283-b904-f1ca28293adc",
  "isSidechain": false,
  "userType": "external",
  "cwd": "/Users/user/code/temp",
  "sessionId": "f0176e14-1a98-424a-bd39-5395e9db94e7",
  "version": "1.0.61",
  "gitBranch": "",
  "type": "user",
  "message": {
    "role": "user",
    "content": "Hello"
  },
  "uuid": "7102fc1c-1148-49cf-8390-4dc13bf4a77e",
  "timestamp": "2025-07-28T22:54:41.466Z"
}
```

## Session Files

- Each session gets its own `.jsonl` file
- File names are UUIDs: `[session-id].jsonl`
- Files grow as conversation continues
- Old sessions are preserved indefinitely