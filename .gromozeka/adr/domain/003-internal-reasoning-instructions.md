# Domain Specification: Internal Reasoning Instructions

**For:** Architect Agent  
**Module:** `:domain`  
**File:** `domain/src/commonMain/kotlin/com/gromozeka/bot/domain/model/Conversation.kt`  
**Related ADR:** [002-internal-reasoning-delegation](../coordination/002-internal-reasoning-delegation.md)

## Task

Add three new `Conversation.Message.Instruction` types to support internal reasoning delegation.

## Requirements

### 1. DelegateReasoning Instruction

**Purpose:** Signal ConversationEngineService to switch to specialized agent.

**Properties:**
- `agentPrompt: String` - complete system prompt for delegated agent (required)
- `markIntermediate: Boolean` - auto-mark messages as intermediate (default: true)

**Serialization:**
- Persist to database via existing Instruction serialization
- XML format for LLM: `<delegate-reasoning>...</delegate-reasoning>` (summary only)

**Implementation:**

```kotlin
@Serializable
data class DelegateReasoning(
    val agentPrompt: String,
    val markIntermediate: Boolean = true
) : Instruction() {
    init {
        require(agentPrompt.isNotBlank()) { "Agent prompt cannot be blank" }
        require(agentPrompt.length <= 50_000) { "Agent prompt too long (max 50,000 chars)" }
    }
    
    override val title = "Delegate Reasoning"
    override val description = "Switch to specialized agent for internal reasoning"
    
    // Full prompt persisted to DB (for restoration and debugging)
    override fun serializeContent() = "delegate_reasoning:${agentPrompt.length}:${agentPrompt.take(200)}"
    
    // XML shows only summary to avoid system prompt bloat
    override fun toXmlLine() = 
        "<delegate-reasoning>Specialist active (${agentPrompt.length} chars)</delegate-reasoning>"
}
```

**Validation:**
- `agentPrompt` must not be blank
- `agentPrompt` maximum length: 50,000 characters
- Validation happens in `init` block

**Rationale for XML summary:**
- Full agentPrompt can be 2000+ characters
- Including it in toXmlLine() bloats system prompt unnecessarily
- LLM only needs to know "delegation is active"
- Full prompt saved via serializeContent() for debugging

---

### 2. ReturnControl Instruction

**Purpose:** Signal return to original agent, ending delegation.

**Properties:** None (singleton object)

**Serialization:**
- XML format: `<return-control>Returning to main agent</return-control>`

**Implementation:**

```kotlin
@Serializable
object ReturnControl : Instruction() {
    override val title = "Return Control"
    override val description = "Return control to main agent"
    override fun serializeContent() = "return_control"
    override fun toXmlLine() = "<return-control>Returning to main agent</return-control>"
}
```

---

### 3. IntermediateReasoning Instruction

**Purpose:** Mark message as intermediate reasoning step (cleanup candidate).

**Properties:** None (singleton object)

**Characteristics:**
- Message visible to user during processing
- Can be referenced by distilled final message
- UI can filter/highlight these for cleanup
- Does not affect LLM processing

**Implementation:**

```kotlin
@Serializable
object IntermediateReasoning : Instruction() {
    override val title = "Intermediate"
    override val description = "Internal reasoning step (can be cleaned up)"
    override fun serializeContent() = "intermediate"
    override fun toXmlLine() = "<intermediate>Internal reasoning</intermediate>"
}
```

---

## Integration Points

### Existing Code

These instructions integrate with:

1. **Message persistence** - Already handled by `Conversation.Message.instructions: List<Instruction>`
2. **Serialization** - Existing kotlinx.serialization handles sealed class polymorphism
3. **Database** - ConversationRepository already stores instructions as JSON
4. **LLM prompts** - SystemPromptBuilder already includes instructions via `toXmlLine()`

### No Changes Required To

- Database schema (instructions stored as JSON column)
- Repository interfaces (already handle List<Instruction>)
- Message model structure (instructions field already exists)
- Serialization infrastructure (sealed class support already present)

---

## Usage Examples

### Example 1: Simple Delegation

```kotlin
// Main agent creates delegation
val delegateMsg = Conversation.Message(
    id = Message.Id(uuid7()),
    conversationId = conversationId,
    role = Role.USER,
    content = listOf(ContentItem.UserMessage("Task for specialist")),
    instructions = listOf(
        Instruction.DelegateReasoning(
            agentPrompt = """
                You are a code architecture analyst.
                Analyze the codebase structure and identify patterns.
                Focus on: modularity, coupling, cohesion.
                Return findings when analysis complete.
            """.trimIndent()
        )
    ),
    createdAt = Clock.System.now()
)
```

### Example 2: Return Control

```kotlin
// Delegated agent returns control
val returnMsg = Conversation.Message(
    id = Message.Id(uuid7()),
    conversationId = conversationId,
    role = Role.ASSISTANT,
    content = listOf(
        ContentItem.AssistantMessage(
            StructuredText("Analysis complete. Found 3 architectural issues.")
        )
    ),
    instructions = listOf(
        Instruction.IntermediateReasoning,  // mark as intermediate
        Instruction.ReturnControl            // return to main
    ),
    createdAt = Clock.System.now()
)
```

### Example 3: Intermediate Marking

```kotlin
// Delegated agent's working message
val workMsg = Conversation.Message(
    id = Message.Id(uuid7()),
    conversationId = conversationId,
    role = Role.ASSISTANT,
    content = listOf(
        ContentItem.AssistantMessage(
            StructuredText("Analyzing module dependencies...")
        )
    ),
    instructions = listOf(
        Instruction.IntermediateReasoning  // auto-added by ConversationEngineService
    ),
    createdAt = Clock.System.now()
)
```

---

## Testing Checklist

After implementation, verify:

- [ ] `DelegateReasoning` serializes/deserializes correctly
- [ ] `DelegateReasoning` validates agentPrompt (blank → exception)
- [ ] `DelegateReasoning` validates agentPrompt length (>50K → exception)
- [ ] `DelegateReasoning.toXmlLine()` returns summary (not full prompt)
- [ ] `DelegateReasoning.serializeContent()` preserves full prompt info
- [ ] `ReturnControl` serializes as object (singleton)
- [ ] `IntermediateReasoning` serializes as object (singleton)
- [ ] All instructions implement abstract methods correctly
- [ ] XML output matches expected format
- [ ] No compilation errors in `:domain` module
- [ ] Existing tests still pass (no regression)

**Compile command:**
```bash
./gradlew :domain:compileKotlin -q || ./gradlew :domain:compileKotlin
```

---

## Design Decisions

### Why sealed class instead of enum?

**Reason:** Instructions carry data (`agentPrompt` in DelegateReasoning). Sealed classes support data-carrying variants, enums don't.

### Why object for ReturnControl and IntermediateReasoning?

**Reason:** No data to carry, singleton sufficient. Serializes cleanly as JSON object without properties.

### Why inline agent prompt instead of Agent.Id reference?

**Reason:** 
- Delegation is dynamic - no need to pre-create agents
- Inline prompt more flexible for one-off specialists
- Avoids database lookup during tool execution
- Temporary agents don't pollute agent registry

### Why markIntermediate default true?

**Reason:** 99% of delegations want intermediate marking. Explicit `false` for rare cases where delegated agent produces final output.

### Why XML shows summary instead of full agentPrompt?

**Problem:** Full agentPrompt can be 2000+ characters. Including it in system prompt via toXmlLine() causes:
- System prompt bloat (wasted tokens)
- LLM confusion (outdated delegation context)
- Performance degradation (larger prompts = slower inference)

**Solution:** 
- toXmlLine() shows only summary: `<delegate-reasoning>Specialist active (2847 chars)</delegate-reasoning>`
- serializeContent() preserves full prompt for DB storage
- Full prompt available for debugging and restoration

**Benefits:**
- Compact XML (30 chars vs 2000+)
- LLM sees "delegation active" status
- Full data preserved in database
- Easy debugging (can see prompt length at glance)

### Why 50,000 character limit for agentPrompt?

**Reason:**
- Prevents accidental large prompts (copy-paste errors)
- Most Claude models: 200K tokens ≈ 800K chars total context
- System prompt + history should leave room for completion
- 50K chars ≈ 12.5K tokens - reasonable for specialist prompt
- Can be increased if needed, but serves as safety guard

---

## Future Enhancements

**Not in scope for initial implementation:**

1. **Nested delegation tracking**
   - Track delegation depth (agent A → B → C)
   - Prevent infinite recursion
   - Breadcrumb trail in UI

2. **Delegation metadata**
   - Reason for delegation
   - Expected completion time
   - Delegation history (who delegated to whom)

3. **Typed agent prompts**
   - Template system for common specialists
   - Validation of prompt structure
   - Reusable specialist definitions

4. **Delegation permissions**
   - Which agents can delegate
   - Maximum delegation depth
   - Tool access control for delegated agents

---

## Summary for Architect Agent

**What to do:**

1. Open `domain/src/commonMain/kotlin/com/gromozeka/bot/domain/model/Conversation.kt`
2. Find `sealed class Instruction` (around line 433)
3. Add three new instruction types as specified above
4. Run compile check: `./gradlew :domain:compileKotlin -q`
5. Verify no compilation errors
6. Commit with message: `feat(domain): Add internal reasoning delegation instructions`

**DO NOT:**
- Modify database schema
- Change repository interfaces  
- Add application logic
- Create MCP tools (that's Spring AI Agent's job)

**Dependencies:**
- None! This is pure domain model addition
- No new imports needed (uses existing Serializable, JsonClassDiscriminator)
- No coordination needed with other agents yet
