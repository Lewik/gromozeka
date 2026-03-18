package com.gromozeka.domain.tool.stride

import com.gromozeka.domain.tool.Tool
import kotlinx.serialization.Serializable
import org.springframework.ai.chat.model.ToolContext

/**
 * Domain specification for user input request tool.
 *
 * # MCP Tool Exposure
 * **Tool name:** `request_user_input`
 * **Exposed via:** Spring AI + MCP protocol
 *
 * # Purpose
 * Request user input when LLM cannot proceed autonomously.
 * Returns question as tool_result → LLM includes in response → execution pauses.
 *
 * # Execution Flow
 *
 * **What happens:**
 * 1. LLM calls request_user_input({ question: "...", ... })
 * 2. Infrastructure returns tool_result with user_response field
 * 3. Tool_result indicates "waiting for user"
 * 4. LLM incorporates question into response
 * 5. LLM response has no more tool calls → loop exits
 * 6. User sees question, types answer
 * 7. User message triggers new execution cycle
 * 8. LLM sees history (question + answer)
 * 9. LLM continues with answer in context
 *
 * **No special orchestration:**
 * - No pause mechanism needed
 * - No mode switching
 * - No resume button
 * - Just natural conversation flow
 *
 * # When to Use
 *
 * **Call when:**
 * - Ambiguous requirement ("should I use X or Y?")
 * - Need confirmation for destructive action
 * - Multiple valid options, no clear preference
 * - Missing information user must provide
 * - Verification uncertain (conflicting data)
 *
 * **Examples:**
 * - "Config file exists. Overwrite or merge?"
 * - "Found 3 TODO patterns: TODO, FIXME, XXX. Which to include?"
 * - "Database migration will delete data. Confirm?"
 * - "Graph shows MySQL, step claims PostgreSQL. Which is correct?"
 *
 * **Do NOT call if:**
 * - Can search/verify autonomously → use tools
 * - Recoverable error → retry
 * - Critical failure → use step_complete(fail)
 * - Step completed → use step_complete(success)
 *
 * # Parameters
 *
 * ## reason: String
 * Why asking: "clarification" | "approval" | "missing_info" | "blocked"
 *
 * - **clarification** - ambiguous requirement, need to clarify intent
 * - **approval** - need user confirmation for risky action
 * - **missing_info** - lack information only user can provide
 * - **blocked** - cannot proceed without user decision
 *
 * ## question: String
 * Clear question for user (1-3 sentences).
 * Include context and available options.
 *
 * Example:
 * "Found existing config.yaml with different database settings. Current file uses MySQL,
 * but step specifies PostgreSQL. Which should I use?"
 *
 * ## options: List<String> (optional)
 * Suggested response options for quick selection.
 * Empty = free-form answer.
 *
 * Example: ["Overwrite", "Merge", "Cancel"]
 *
 * ## context: String (optional)
 * Additional context for decision.
 * Can include relevant facts, file contents, etc.
 *
 * # Response (tool_result from infrastructure)
 *
 * **Immediate response (before user answers):**
 * ```json
 * {
 *   "status": "waiting_for_user",
 *   "question": "Which TODO patterns to search: TODO only, TODO + FIXME, or all?",
 *   "options": ["TODO only", "TODO + FIXME", "All"]
 * }
 * ```
 *
 * LLM incorporates this into text response, no more tool calls.
 * Loop exits naturally.
 *
 * **After user answers (in new execution cycle):**
 * User message added to context, LLM sees full history and continues.
 *
 * # State Preservation
 *
 * **During wait:**
 * - Step.status remains IN_PROGRESS
 * - Plan.status remains ACTIVE
 * - No Neo4j state changes
 *
 * **After answer:**
 * - LLM sees question + answer in history
 * - Continues execution naturally
 * - Can call step_complete, update_plan, or continue work
 *
 * # UI Presentation
 *
 * **Question display:**
 * - Appears as assistant message
 * - If options provided → show as buttons/quick replies
 * - If no options → regular text input
 *
 * **User response:**
 * - Normal message input
 * - No special "Resume" button needed
 * - Just type answer and send
 *
 * # Question Quality
 *
 * **Be specific:**
 * - Include relevant context
 * - Explain why asking
 * - Show current state vs. proposed change
 *
 * **Provide options when possible:**
 * - Faster for user
 * - Clearer intent
 * - Easier to parse answer
 *
 * **Explain consequences:**
 * - What happens with each choice
 * - Risks/benefits
 * - Default behavior if any
 *
 * # Examples
 *
 * **Clarification:**
 * ```json
 * {
 *   "reason": "clarification",
 *   "question": "Step says 'search TODO'. Should I search TODO comments in code, or TODO files in project structure?",
 *   "options": ["TODO comments in code", "TODO files", "Both"]
 * }
 * ```
 *
 * **Approval:**
 * ```json
 * {
 *   "reason": "approval",
 *   "question": "About to delete 150 test files as part of cleanup. This is irreversible. Proceed?",
 *   "options": ["Yes, delete", "No, cancel", "Show list first"],
 *   "context": "Files matched pattern: *Test.kt in test/ directory"
 * }
 * ```
 *
 * **Missing info:**
 * ```json
 * {
 *   "reason": "missing_info",
 *   "question": "Need database password to test connection. Password for PostgreSQL user 'admin'?",
 *   "context": "Connection string: postgresql://admin@localhost:5432/gromozeka"
 * }
 * ```
 *
 * **Blocked:**
 * ```json
 * {
 *   "reason": "blocked",
 *   "question": "Config file exists with conflicting settings. Current: MySQL, Step: PostgreSQL. Which is correct?",
 *   "options": ["Use current (MySQL)", "Use step (PostgreSQL)", "Ask me to decide manually"],
 *   "context": "Current config created 2 days ago. Step certainty: 0.6 (uncertain)."
 * }
 * ```
 *
 * # Common Patterns
 *
 * **Conflicting information:**
 * ```
 * Graph: "project uses MySQL"
 * Step: "project uses PostgreSQL" (certainty: 0.6)
 * → request_user_input(reason="blocked", question="Which DB is correct?")
 * ```
 *
 * **Destructive action:**
 * ```
 * Command: "delete old logs"
 * Found: 5000 files, 2GB
 * → request_user_input(reason="approval", question="Delete 5000 files (2GB)?")
 * ```
 *
 * **Ambiguous requirement:**
 * ```
 * Step: "improve performance"
 * Options: cache, optimize queries, add indexes, scale horizontally
 * → request_user_input(reason="clarification", question="Which optimization?")
 * ```
 */
interface RequestUserInputTool : Tool<RequestUserInputRequest, RequestUserInputResponse> {
    override val name: String get() = "request_user_input"
    override val description: String get() = "Request user input to resolve ambiguity or uncertainty"
    override val requestType: Class<RequestUserInputRequest> get() = RequestUserInputRequest::class.java

    override fun execute(request: RequestUserInputRequest, context: ToolContext?): RequestUserInputResponse
}

@Serializable
data class RequestUserInputRequest(
    val reason: String,  // "clarification" | "approval" | "missing_info" | "blocked"
    val question: String,
    val options: List<String> = emptyList(),
    val context: String? = null
)

@Serializable
data class RequestUserInputResponse(
    val status: String,  // "waiting_for_user"
    val question: String,
    val options: List<String> = emptyList()
)
