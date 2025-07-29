# ProcessBuilder Hanging Issue with Claude Code CLI

## Problem

When using `ProcessBuilder` to invoke Claude Code with the `--print` flag, the process hangs indefinitely:

```kotlin
// THIS HANGS:
ProcessBuilder("claude", "--print", "hello").start()
```

## Root Cause

The `--print` flag appears to have issues in non-interactive subprocess mode. This is likely related to the GitHub issue #3976 about session management in non-interactive mode.

## Solution

Use pipe approach through bash:

```kotlin
// THIS WORKS:
ProcessBuilder("bash", "-c", "echo 'hello' | claude --output-format stream-json").start()
```

## Why This Works

- The pipe approach simulates interactive input
- Bash handles the process communication properly
- Claude Code receives input through stdin rather than command arguments

## Alternative Approaches That Don't Work

```kotlin
// Also hangs:
ProcessBuilder("claude", "--output-format", "json", "--print", "hello").start()

// Hangs with any output format:
ProcessBuilder("claude", "--output-format", "stream-json", "--print", "hello").start()
```

## Recommendation

Always use the pipe approach for programmatic Claude Code invocation from JVM-based applications.