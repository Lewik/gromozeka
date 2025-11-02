# Domain Model

## Core Architecture Decisions

### Why Messages are Immutable

Messages are shared between Threads by reference, not copied:
- Thread copy = INSERT new Thread + reference same Messages
- Enables full audit trail without storage duplication
- Message belongs to Conversation (not Thread) for this reason

### Thread Mutability Model

**Default: append-only**
- User sends message → INSERT into thread_messages
- Fast, no Thread copy needed

**Explicit operations create new Thread**:
- Delete/Edit/Reorder/Squash → CREATE new Thread + reference Messages
- originalThread tracks derivation
- Enables time travel: traverse Thread chain backward

**Why this design?**
- 99% of operations are append (fast path)
- Complex operations are explicit and tracked (audit trail)
- Undo/redo via Thread switching

### Message.originalIds Semantics (DEPRECATED)

**Status**: Deprecated in favor of `squashOperationId` for structured provenance tracking.

Not just "parent message", but provenance tracking:
- `[]`: new message
- `[id]`: edited version
- `[id1, id2, ...]`: squash result

**Non-obvious aspects**:
- List, not single value: squash can merge multiple messages
- Source messages can be non-contiguous
- Forms DAG, not tree (message can be squashed multiple times in different contexts)

**Migration**: New code should use `SquashOperation` entity for tracking squash operations.

### SquashOperation is Immutable

SquashOperation tracks AI/manual squash provenance:
- `prompt` can be null (manual edit or prompt not saved)
- `performedByAgent` flag: true = AI squash, false = manual
- `sourceMessageIds` references original Messages (immutable)
- `resultMessageId` points to squashed Message

**Why separate entity?**
- Multiple Messages can be squashed → one SquashOperation
- Reproducibility: can re-run squash with same prompt
- Audit trail: who squashed and how
- Structured provenance vs flat list in originalIds

### Squash is AI Operation

Squash ≠ concatenation:
- AI summarization (reduce verbosity, keep essence)
- Restructuring (change format, reorganize)
- Semantic compression

**Why this matters**:
- SquashOperation preserves prompt and model used
- Squash prompt determines logic (not hardcoded)
- Can squash non-adjacent messages (e.g., "combine all tool calls")
- performedByAgent distinguishes AI squash from manual edit

### Why conversationId in Message, not threadId

Messages can appear in multiple Threads:
- Edit message → new Thread references same original Message
- Squash messages → new Thread references same source Messages
- conversationId is stable, threadId would change

### Inter-Agent Communication via Instructions

Instruction types attached to Messages:
- **Source.User**: from human
- **Source.Agent(tabId)**: from another agent
- **ResponseExpected(tabId)**: reply routing

**Flow**:
1. Agent A sends message via `tell_agent`
2. Message tagged with Source.Agent(A) + ResponseExpected(A)
3. Agent B receives, processes
4. Agent B checks ResponseExpected → uses tell_agent back to A

**Why in domain model?**
- Multi-agent coordination is first-class concern
- Message routing is business logic, not infrastructure

## Time Travel Implementation

**User wants to undo squash operation**:
1. Current Thread has originalThread → previous Thread
2. Switch Conversation.currentThread to previous
3. All Messages still exist (immutable)
4. UI shows previous state

**Can traverse chain**:
```
Thread_initial → Thread_after_squash → Thread_after_edit → Thread_current
                 ↑ can go back here
```

## Storage Strategy (Inferred)

Three tables:
- `messages`: immutable, never deleted
- `threads`: tracks originalThread chain
- `thread_messages`: JOIN table, mutable

**Thread copy operation**:
- INSERT INTO threads (new id, original_thread_id=old_id)
- INSERT INTO thread_messages (new_thread_id, message_id, position)
  - message_id references same Messages!

**No Message duplication** → storage efficient + full history.

## Context File Specifications

Context.FileSpec allows granular file inclusion:
- **ReadFull**: entire file (for small files)
- **SpecificItems**: list of strings
  - Function names: `"fun calculateTotal"`
  - Class names: `"class UserService"`
  - Line ranges: `"142:156"`

**Why?**
- AI context window is expensive
- Often need specific functions, not entire file
- Content-addressable: similar to Git addressing

**Extraction prompt determines format** (function signature vs full body).

## MessageTag UI Binding

MessageTagDefinition binds UI controls to domain Instructions:
- User clicks button → Instruction added to Message
- includeInMessage flag: some controls just trigger action, don't modify message

**Example**: "Response Expected" button
- Creates Instruction.ResponseExpected(targetTabId)
- Added to message → Agent B knows to reply via tell_agent

**Why in domain?**
- UI state tied to message semantics
- Need to persist which Instructions were active
- Resume session requires restoring UI state from domain

## Message.replyTo (Future Feature)

**Status**: Field reserved, not implemented yet.

Reply threading within linear Thread:
- User replies to specific message from history
- UI can show thread tree
- `replyTo` field points to parent message

**Use cases**:
- Branching discussion from specific point
- Clarifying questions about earlier messages
- Multi-threaded conversation within single Thread

**Implementation TODO**:
- UI for selecting message to reply to
- Thread visualization component
- Navigation between reply chains
