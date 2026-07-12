# Work-ready UX roadmap

## Goal

Make Gromozeka reliable and understandable enough for daily professional use without turning it into a simplified consumer chat application.

The immediate priority is supervision UX rather than visual polish: the user must be able to understand what is happening, notice when attention is required, recover from failures, and verify the outcome.

## Product principles

- Gromozeka is a professional power-user tool. Useful capabilities should remain discoverable.
- Tools may stay visible, but inactive tools must not compete with the active task.
- UI controls must describe real guarantees. A prompt instruction must not look like an enforced permission boundary.
- User-facing state should use domain language. Worker IDs, payload types, and raw traces belong in expandable diagnostics.
- Long-running work must not require continuously watching the conversation.
- Reliability and recovery take priority over cosmetic redesign.
- Implement and validate one vertical slice at a time.

## Existing strengths

- Durable conversation runtime and runtime snapshots.
- Queued messages with end-of-turn and safe mid-turn steering placement.
- Pause, resume, stop, and interrupt controls.
- Persistent per-tab draft input.
- Runtime event cursors that can support lossless resubscription.
- Expandable tool calls and diagnostic traces.

## Priority 0: required for daily work

### 1. Enforced execution modes

**Current problem:** `Readonly` and `Writable` are user instructions sent to the model. `Readonly` looks like a permission boundary in the UI but does not technically prevent a mutating tool call.

**Target behavior:** execution mode is enforced before a tool with side effects reaches a worker. The model receives the same mode as context, but correctness does not depend on model compliance.

**Acceptance criteria:**

- Tools declare machine-readable effects or required permissions.
- Read-only mode rejects mutating tools deterministically.
- Rejection is visible to both the model and user.
- UI wording distinguishes enforced permissions from behavioral instructions.
- The policy applies equally to local and remote workers.

### 2. Connection recovery and resubscription

**Current problem:** after a WebSocket read-loop failure, active conversation subscriptions are closed. A later request can reconnect the socket, but existing observations are not automatically restored.

**Target behavior:** the client exposes connection state, reconnects with backoff, resubscribes from the last event cursor, and reconciles with a fresh runtime snapshot.

**Acceptance criteria:**

- UI distinguishes connected, reconnecting, and offline states.
- Active conversation subscriptions survive a transient disconnect.
- Events are resumed without duplication or loss.
- Draft input and queued messages remain intact.
- A failed reconnect produces an actionable error instead of silent staleness.

### 3. Conversation attention model

**Current problem:** conversations are difficult to distinguish and require opening them to discover whether work is active, complete, failed, or waiting for the user.

**Target behavior:** every conversation has a meaningful title and an attention state visible in tabs and the conversation list.

**Suggested states:**

- Idle
- Working
- Waiting for user
- Completed with unread result
- Failed
- Paused

**Acceptance criteria:**

- Blank conversations receive a useful automatic title derived from their first request.
- Tabs and the conversation list show status without opening the conversation.
- The user can jump to the first unread result.
- Important conversations can be pinned; inactive ones can be archived.

### 4. Attention notifications

**Target behavior:** notify only when the user can or should act.

**Notification events:**

- Execution completed while the app was not focused.
- User decision or approval is required.
- Execution failed.
- A paused or blocked conversation can continue.

Routine tool progress should remain inside the application.

### 5. User-facing recovery

**Target behavior:** failures expose relevant recovery actions instead of only logs or internal retries.

**Initial actions:**

- Retry the failed operation or turn.
- Restore the unsent text to the composer.
- Copy diagnostic details.
- Stop the current execution cleanly.

## Priority 1: high-value professional workflows

### 6. Human-readable progress and return recap

- Show meaningful stages such as memory recall, model request, tool execution, verification, and memory write.
- Show elapsed time for the active stage.
- Keep raw runtime state and trace available as diagnostics.
- When the user returns, summarize what happened and what currently needs attention.
- For long goals, expose the agent's task list or milestones.

### 7. Context-aware composer and command palette

- `@` references for files, folders, conversations, memory sources, and other context.
- `/` command palette for discoverable professional actions.
- Visible context chips before sending.
- Searchable keyboard shortcut reference and prompt history.

This should complement visible tools, not become a menu used only to hide them.

### 8. Outcome and side-effect review

- Summarize files changed, commands run, tests executed, artifacts created, and external side effects.
- Keep raw tool calls expandable beneath the summary.
- Open changed files or diffs in the appropriate local tool.
- Do not claim reversibility unless the underlying worker provides it.

### 9. Memory transparency and control

- Show which memory context was used for a turn.
- Show memory write status and a concise summary of created or changed records.
- Allow inspection, correction, forgetting, and later rollback where supported.
- Keep memory pipeline diagnostics separate from the ordinary user summary.

### 10. Message-level recovery and branching

- Edit and resend a previous request.
- Retry an assistant response.
- Fork from a selected message rather than only from the latest state.
- Add checkpoints only after side effects and restoration boundaries are modeled honestly.

## Not an immediate priority

- Broad visual redesign or theme work.
- Consumer-style onboarding and oversized call-to-action controls.
- UX for distributed worker routing before the basic worker model is needed in practice.
- Generic checkpoint machinery that cannot restore external side effects.
- Hiding professional tools solely to make the interface look minimal.

## Recommended implementation order

1. Enforced execution modes.
2. Connection recovery and resubscription.
3. Conversation titles and attention state.
4. Notifications.
5. User-facing recovery.
6. Human-readable progress and return recap.
7. Context composer and command palette.
8. Outcome review.
9. Memory transparency.
10. Message-level branching and checkpoints.

## Reference patterns

- VS Code chat sessions: session status, unread activity, pinning, and archiving.
- VS Code chat: queued messages, notifications, context references, and debugging surfaces.
- Claude Code: task list, session recap, command history, shortcuts, and status line.
- Codex and Cursor: reviewable diffs, changed-file summaries, and checkpoints.
