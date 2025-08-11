# Claude Code Streaming Behavior Documentation

## How to Reproduce and Verify Double Init Behavior

### Step-by-step Instructions

```bash
# 1. Navigate to .sources directory (create if doesn't exist)
cd /Users/lewik/code/gromozeka/dev
mkdir -p .sources
cd .sources

# 2. Clone the Python SDK (or pull if exists)
if [ -d "claude-code-sdk-python" ]; then
    cd claude-code-sdk-python
    git pull
else
    git clone https://github.com/anthropics/claude-code-sdk-python.git
    cd claude-code-sdk-python
fi

# 3. Create and activate virtual environment
python3 -m venv venv
source venv/bin/activate

# 4. Install the SDK and dependencies
pip install -e .
pip install typing_extensions

# 5. Apply debug logging patches
cat > apply_debug_patches.py << 'EOF'
import fileinput
import sys

# Patch subprocess_cli.py to add debug logging
file_path = "src/claude_code_sdk/_internal/transport/subprocess_cli.py"

patches = [
    ("                await self._stdin_stream.send(json.dumps(message) + \"\\n\")",
     "                json_line = json.dumps(message)\n                print(f\"[DEBUG SDK] Sending to stdin: {json_line}\")\n                await self._stdin_stream.send(json_line + \"\\n\")"),
    
    ("            await self._stdin_stream.send(json.dumps(message) + \"\\n\")",
     "            json_line = json.dumps(message)\n            print(f\"[DEBUG SDK] send_request sending: {json_line}\")\n            await self._stdin_stream.send(json_line + \"\\n\")"),
    
    ("                        try:\n                            yield data",
     "                        print(f\"[DEBUG SDK] Received from stdout: {json.dumps(data)}\")\n                        try:\n                            yield data"),
    
    ("        cmd = self._build_command()\n        try:",
     "        cmd = self._build_command()\n        print(f\"[DEBUG SDK] Command: {' '.join(cmd)}\")\n        try:")
]

content = open(file_path, 'r').read()
for old, new in patches:
    content = content.replace(old, new)
open(file_path, 'w').write(content)
print("Debug patches applied successfully!")
EOF

python apply_debug_patches.py

# 6. Create test script
cat > test_double_init.py << 'EOF'
#!/usr/bin/env python3
"""Test to demonstrate double init behavior in Claude Code streaming mode"""

import asyncio
from claude_code_sdk import ClaudeSDKClient

async def test_double_init():
    print("=== Testing Double Init Behavior ===\n")
    print("Expected: Each user message will trigger a new init message from Claude\n")
    
    async with ClaudeSDKClient() as client:
        print("[TEST] Sending FIRST message...")
        await client.query("Say 'one'")
        
        print("\n[TEST] Waiting for FIRST response...")
        init_count = 0
        async for msg in client.receive_response():
            if hasattr(msg, '__class__'):
                msg_type = msg.__class__.__name__
                print(f"[TEST] Received: {msg_type}")
                if msg_type == "SystemMessage":
                    init_count += 1
                    print(f"[TEST] >>> INIT #{init_count} received <<<")
        
        print(f"\n[TEST] After first message: {init_count} init(s) received")
        
        print("\n[TEST] Sending SECOND message...")
        await client.query("Say 'two'")
        
        print("\n[TEST] Waiting for SECOND response...")
        async for msg in client.receive_response():
            if hasattr(msg, '__class__'):
                msg_type = msg.__class__.__name__
                print(f"[TEST] Received: {msg_type}")
                if msg_type == "SystemMessage":
                    init_count += 1
                    print(f"[TEST] >>> INIT #{init_count} received <<<")
        
        print(f"\n[TEST] TOTAL INITS RECEIVED: {init_count}")
        print("[TEST] If you see 2 inits, the double init behavior is confirmed!")
    
    print("\n=== Test Complete ===")

if __name__ == "__main__":
    print("Running test to verify double init behavior...\n")
    asyncio.run(test_double_init())
EOF

# 7. Run the test
python test_double_init.py
```

### What You Will See

When you run the test, you'll observe:

1. **First message** ("Say 'one'"):
   - Sends: `{"type": "user", "message": {"role": "user", "content": "Say 'one'"}, ...}`
   - Receives: `{"type": "system", "subtype": "init", ...}` - **INIT #1**
   - Receives: Assistant response
   - Receives: Result message

2. **Second message** ("Say 'two'"):
   - Sends: `{"type": "user", "message": {"role": "user", "content": "Say 'two'"}, ...}`
   - Receives: `{"type": "system", "subtype": "init", ...}` - **INIT #2** (same session_id as #1)
   - Receives: Assistant response
   - Receives: Result message

### Expected Output Summary

```
[TEST] >>> INIT #1 received <<<
[TEST] After first message: 1 init(s) received
[TEST] >>> INIT #2 received <<<
[TEST] TOTAL INITS RECEIVED: 2
```

This confirms that **Claude Code sends an init message after each user message** in stream-json mode.

## Double Init Messages in Stream-JSON Mode

### Discovery

During investigation of the Claude Code CLI behavior in streaming mode, we discovered that **receiving multiple `init` messages is normal and expected behavior**. This was confirmed by analyzing the official `claude-code-sdk-python` implementation.

### Test Results from Python SDK

When running the Python SDK tests with debug logging enabled, we observed the following pattern:

#### Test 1: Basic Streaming Mode
```python
async with ClaudeSDKClient() as client:
    await client.query("What is 2+2?")  # First message
    # Response received...
    await client.query("What is 3+3?")  # Second message
```

**Observed behavior:**
1. First message sent: `{"type": "user", "message": {"role": "user", "content": "What is 2+2?"}, "parent_tool_use_id": null, "session_id": "default"}`
2. **First init received**: `{"type": "system", "subtype": "init", "session_id": "39a36a94-c2bf-49b2-bce2-4524f504adc3", ...}`
3. Assistant response received
4. Result message received
5. Second message sent: `{"type": "user", "message": {"role": "user", "content": "What is 3+3?"}, "parent_tool_use_id": null, "session_id": "default"}`
6. **Second init received**: Same session ID as first init
7. Assistant response received
8. Result message received

### Key Findings

1. **Multiple init messages are normal**: Claude Code sends an `init` message after receiving each new user message in stream-json mode.

2. **Session ID behavior**: 
   - Client sends `session_id` in messages (SDK uses "default" by default)
   - Claude Code ignores the client-provided session_id
   - Claude Code generates its own UUID and uses it consistently across all init messages

3. **Python SDK handling**: The SDK simply receives and parses all messages without treating repeated init messages as errors.

### Command Format

The correct command format for streaming mode:
```bash
claude --output-format stream-json --input-format stream-json --verbose [other-options]
```

Note: In streaming mode, `--print` flag is NOT used. All messages are sent via stdin.

### Message Format for Streaming

Input format (sent to stdin):
```json
{
  "type": "user",
  "message": {
    "role": "user",
    "content": [
      {
        "type": "text",
        "text": "message content"
      }
    ]
  },
  "parent_tool_use_id": null,
  "session_id": "session-id"
}
```

Output format (received from stdout):
- `{"type": "system", "subtype": "init", ...}` - Session initialization (repeated for each message)
- `{"type": "assistant", ...}` - Claude's responses
- `{"type": "user", ...}` - Tool results
- `{"type": "result", ...}` - Turn completion

### Recommendation for Implementation

When implementing a Claude Code streaming wrapper:
1. **Accept multiple init messages** as normal behavior
2. **Use the session_id from the first init** for session tracking
3. **Ignore subsequent init messages** or treat them as session confirmations
4. **Don't treat repeated init as an error**

### Source

This behavior was confirmed by:
- Direct testing of `claude-code-sdk-python` v0.0.19
- Adding debug logging to `subprocess_cli.py` 
- Running streaming mode examples with multiple sequential messages
- Observing the exact message flow between the SDK and Claude Code CLI