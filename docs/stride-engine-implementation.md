# Stride Engine - Archived Concept

This feature is intentionally removed from active runtime code. The remaining
document preserves the idea, tool protocol and prompt shape in case we want to
reuse the concept later without carrying the old implementation.

## Concept Worth Keeping

Stride treated a user message as a set of semantic units instead of one opaque
request. The model first had to decompose the message into explicit steps, then
react to every step through tool-result feedback:

- `create_plan` forced the model to convert the user message into typed steps.
- Each step carried text, type, certainty, entities and dependencies.
- Tool results drove the model into the next step, so it could not silently skip
  parts of a dense message.
- `step_complete`, `update_plan`, `request_user_input` and `notify` made the
  model expose progress and uncertainty instead of burying it inside prose.
- Informational units were still processed, not only command-like tasks: facts,
  corrections, opinions, commitments and conditions all got explicit reactions.

The real implementation became too heavy for the current architecture, so the
runtime, UI toggle, Neo4j repositories and active tool beans are archived. The
prompts and examples below are historical notes, not current behavior.

## Core Principle

> **Никакого отдельного orchestrator framework не нужно — вся сложность в промптах и tools, не в control flow.**

Stride Engine работает ВНУТРИ существующего while loop в `ConversationEngineService`.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│ ConversationEngineService.sendMessage()                     │
│                                                              │
│ if (conversation.strideEnabled && iterationCount == 0)     │
│     force tool_choice = "plan_steps"                        │
│                                                              │
│ while (iterationCount < 200) {                             │
│     chatResponse = chatModel.call(currentPrompt)           │
│                                                              │
│     if (hasToolCalls) {                                     │
│         executeParallel(toolCalls)                          │
│         continue  // LLM sees results, decides next        │
│     } else {                                                │
│         break  // Final response, no more tools            │
│     }                                                        │
│ }                                                            │
└─────────────────────────────────────────────────────────────┘
```

## Application Level Changes

**ONE LINE** to enable Stride Engine:

```kotlin
// In ConversationEngineService.sendMessage(), before first LLM call:

val toolOptions = if (conversation.strideEnabled && iterationCount == 0) {
    // Force plan_steps on first iteration
    collectToolOptions(project.path, provider).copy(
        toolChoice = ToolChoice.REQUIRED("plan_steps")
    )
} else {
    // Normal mode - LLM decides
    collectToolOptions(project.path, provider)
}
```

That's it. Everything else is handled by:
- System prompts (instruct LLM how to use Stride Engine)
- Tool implementations (manage Neo4j state)
- LLM (follows instructions, calls tools)

## Execution Flow

### Step 1: Plan Creation

```
User: "Find all TODO in project, group by priority"
↓
App: force tool_choice = "plan_steps"
↓
LLM: calls plan_steps(message="...")
↓
Tool: creates Plan in Neo4j with Steps:
  - Step 1: "Find all TODO in project gromozeka"
  - Step 2: "Group them by priority"
Returns: "Plan created with 2 steps. Start with step 1."
↓
Loop continues (hasToolCalls = true)
```

### Step 2-N: Step Execution

```
LLM sees: "Start with step 1: Find all TODO"
↓
LLM: "I need to search files"
Calls: grz_execute_command("rg 'TODO' --type kt")
↓
Tool: executes, returns "Found 15 matches in 8 files"
↓
Loop continues
↓
LLM: "Step complete"
Calls: step_complete(stepId="...", result="Found 15 TODO items")
↓
Tool: updates Neo4j, returns "Step 1 done. Next: step 2 - Group by priority"
↓
Loop continues
↓
LLM: executes step 2...
Calls: step_complete(stepId="...", result="Grouped by priority: High(3), Medium(7), Low(5)")
↓
Tool: returns "All steps completed"
↓
LLM: provides final summary (NO tool calls)
↓
Loop exits (hasToolCalls = false)
```

### Edge Case: ask_user

```
LLM: calls ask_user(question="Which TODO patterns to include?", options=["TODO only", "TODO + FIXME", "All"])
↓
Tool: returns question text (NOT a tool call)
↓
LLM: includes question in response, no more tool calls
↓
Loop exits (hasToolCalls = false)
↓
User sees question: "Which TODO patterns should I search for: TODO only, TODO + FIXME, or all?"
↓
User answers: "All of them"
↓
New sendMessage call starts
↓
LLM sees history: [question, user answer]
↓
LLM: continues step execution with answer
```

## Implementation Checklist

### Domain Layer (DONE ✅)
- [x] `Plan` and `Step` entities
- [x] `PlanRepository` interface
- [x] `StepRepository` interface
- [x] `DiscourseEngineService` interface
- [x] Flow control tools: `plan_steps`, `step_complete`, `step_failed`, `ask_user`, `notify`
- [x] `Conversation.strideEnabled` field

### Infrastructure Layer (TODO)
- [ ] Neo4j repositories implementation
- [ ] Flow control tools implementation
- [ ] System prompt additions (Stride Engine instructions)

### Application Layer (TODO)
- [ ] `DiscourseEngineService` implementation
- [ ] One-line change in `ConversationEngineService.sendMessage()`

### Presentation Layer (TODO)
- [ ] Stride Mode toggle UI
- [ ] Plan visualization (optional, nice-to-have)

## Key Implementation Details

### Neo4j Schema

```cypher
CREATE (plan:Plan {
  id: "uuid",
  conversationId: "uuid",
  status: "EXECUTING",
  createdAt: timestamp
})

CREATE (step:Step {
  id: "uuid",
  planId: "uuid",
  text: "Find all TODO",
  type: "COMMAND",
  certainty: 1.0,
  entities: ["TODO"],
  position: 0,
  status: "PENDING"
})

CREATE (plan)-[:HAS_STEP]->(step)
CREATE (step1)-[:DEPENDS_ON]->(step0)
```

### Tool Response Format

Tools return plain text/JSON that LLM sees and incorporates:

```kotlin
// plan_steps response
{
  "steps": [...],
  "message": "Plan created with 3 steps. Start with step 1: Find all TODO"
}

// step_complete response
{
  "status": "completed",
  "nextStep": {
    "id": "uuid",
    "text": "Group them by priority",
    "type": "COMMAND"
  },
  "message": "Step 1 done. Next: step 2 - Group by priority"
}

// ask_user response (causes loop exit)
{
  "question": "Which TODO patterns to search?",
  "options": ["TODO only", "TODO + FIXME", "All"]
}
```

### System Prompt Additions

Add to system prompt when `strideEnabled`:

```
# Stride Engine Mode

You are in Stride Engine mode. Follow these steps:

1. FIRST CALL: Use plan_steps tool to decompose user message into execution steps
2. Execute each step sequentially
3. After completing a step, call step_complete to mark it done and get next step
4. If you need user input, call ask_user with your question
5. If a step fails, call step_failed with error details
6. When all steps are complete, provide a summary

Step types guide execution:
- COMMAND/QUERY: Execute with tools (ReAct pattern)
- INFORM/COMMIT/CORRECT/CONDITION/EVALUATE: Quick passthrough to graph

Always call step_complete after finishing a step.
```

## Testing Strategy

1. **Unit tests**: Repository and Service implementations
2. **Integration tests**: Tool implementations with real Neo4j
3. **Manual tests**: End-to-end with real LLM
   - Simple plan (2-3 steps)
   - Plan with dependencies
   - Plan with ask_user
   - Plan with step_failed

## Migration Path

1. **Phase 1**: Implement domain specs (✅ DONE)
2. **Phase 2**: Implement repositories (Neo4j)
3. **Phase 3**: Implement tools
4. **Phase 4**: Add system prompt logic
5. **Phase 5**: One-line change in ConversationEngineService
6. **Phase 6**: UI toggle
7. **Phase 7**: Test and iterate

## FAQs

**Q: Do we need a separate orchestrator?**
A: No. The existing while loop IS the orchestrator.

**Q: How does ask_user pause execution?**
A: It doesn't "pause" - it returns text, LLM includes it in response without tool calls, loop exits naturally.

**Q: What if LLM doesn't follow instructions?**
A: System prompt quality is critical. Include clear examples in prompt.

**Q: How do we handle errors?**
A: step_failed tool marks step as failed, invalidates dependents, returns error text → loop exits.

**Q: Can user cancel mid-execution?**
A: Yes - interrupt LLM call (existing functionality), then user can send new message.

**Q: How is this different from ReAct?**
A: Stride Engine USES ReAct for individual steps. It adds planning layer on top.
