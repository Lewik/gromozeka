package com.gromozeka.domain.tool.stride

import com.gromozeka.domain.tool.Tool
import kotlinx.serialization.Serializable
import org.springframework.ai.chat.model.ToolContext

/**
 * Domain specification for user input request tool.
 *
 * # MCP Tool Exposure
 * **Tool name:** `ask_user`
 * **Exposed via:** Spring AI + MCP protocol
 *
 * # Purpose
 * Request user input during step execution when agent cannot proceed autonomously.
 * Returns question as TEXT (not tool call) → LLM includes in response → loop exits automatically.
 *
 * ## Core Features
 *
 * **Simple text return:**
 * - Tool returns question text (NOT a tool call)
 * - LLM includes question in its response
 * - Response has no tool calls → while loop exits
 * - User sees question, answers normally
 * - New user message → new sendMessage call → execution continues
 *
 * **No special orchestration needed:**
 * - No pause mechanism required
 * - No manual mode switching
 * - No resume button needed
 * - Just natural conversation flow
 *
 * **Context preservation:**
 * - Step state preserved in Neo4j (status = EXECUTING)
 * - LLM sees full history (question + user answer)
 * - Continues execution with answer in context
 *
 * # When to Use
 *
 * **Call when:**
 * - Ambiguous requirement ("should I use X or Y?")
 * - Need confirmation for destructive action
 * - Multiple valid options, no clear preference
 * - Verification uncertain (certainty ~0.5)
 * - Missing information that user must provide
 *
 * **Examples:**
 * - "Config file already exists. Overwrite or merge?"
 * - "Found 3 TODO patterns: TODO, FIXME, XXX. Which ones to include?"
 * - "Database migration will delete data. Confirm?"
 * - "Step claims PostgreSQL, but graph shows MySQL. Which is correct?"
 *
 * **Do NOT call if:**
 * - Can search/verify autonomously → use tools
 * - Recoverable error → retry in ReAct loop
 * - Critical failure → use `step_failed`
 * - Step completed → use `step_complete`
 *
 * # Parameters
 *
 * ## stepId: String
 * UUID of current step (provided by application at step start).
 *
 * ## question: String
 * Clear question for user (1-3 sentences).
 * Should include context and available options.
 *
 * Example:
 * - "Found existing config.yaml with different database settings. Current file uses MySQL, but step specifies PostgreSQL. Which should I use?"
 * - "Step verification uncertain: graph shows 'project uses MySQL' but this step claims 'PostgreSQL'. Is this a correction or an error?"
 *
 * ## options: List<String> (optional)
 * Suggested response options for quick selection.
 * If empty, user provides free-form answer.
 *
 * Example:
 * ```json
 * {
 *   "question": "Which TODO patterns to search?",
 *   "options": ["TODO only", "TODO + FIXME", "All (TODO, FIXME, XXX)"]
 * }
 * ```
 *
 * ## context: String (optional)
 * Additional context for user decision.
 * Can include relevant facts from graph, file contents, etc.
 *
 * # Response
 *
 * Returns formatted question text (will be included in LLM response):
 * ```json
 * {
 *   "question": "Which TODO patterns to search: TODO only, TODO + FIXME, or all?",
 *   "options": ["TODO only", "TODO + FIXME", "All (TODO, FIXME, XXX)"]
 * }
 * ```
 *
 * LLM sees this response and includes question in its text response.
 * Since LLM doesn't make more tool calls, while loop exits naturally.
 *
 * # User Interaction Flow
 *
 * **What happens:**
 * 1. LLM calls ask_user tool
 * 2. Tool returns question text
 * 3. LLM includes question in response (no more tool calls)
 * 4. While loop exits (no tool calls = done)
 * 5. User sees question in chat
 * 6. User types answer
 * 7. New sendMessage call starts
 * 8. LLM sees history with question + answer
 * 9. LLM continues step execution with answer
 *
 * **No special UI needed:**
 * - Question appears as regular assistant message
 * - User answers in normal input field
 * - No "Resume" button required
 * - No mode switching needed
 *
 * # Tool Behavior
 *
 * **Response format:**
 * - Tool returns question text with optional choices
 * - Response is plain text/JSON (not a tool call)
 *
 * **Step state:**
 * - Step.status remains EXECUTING (not completed)
 * - Step awaits user input to continue
 *
 * **Execution flow:**
 * - LLM receives tool response containing question
 * - LLM incorporates question into natural language response
 * - LLM does not produce additional tool calls
 * - Execution loop terminates (no tool calls present)
 * - User message resumes execution with answer in context
 *
 * **Question quality guidelines:**
 * - Be specific: include relevant context
 * - Provide options when possible (faster for user)
 * - Explain consequences of choices
 * - Show current state vs. proposed change
 *
 * **Verification use case:**
 * When step has `certainty < 1.0` and verification is uncertain:
 * ```
 * Step: "Project uses PostgreSQL"
 * Certainty: 0.6
 * Verification: graph shows "MySQL"
 * 
 * → ask_user(
 *     question = "Conflicting database info. Graph: MySQL, Step: PostgreSQL. Correct?",
 *     options = ["Graph is right (MySQL)", "Step is right (PostgreSQL)", "Neither"]
 *   )
 * ```
 */
interface AskUserTool : Tool<AskUserRequest, AskUserResponse> {
    override val name: String get() = "ask_user"
    override val description: String get() = "Request user input to resolve ambiguity or uncertainty"
    override val requestType: Class<AskUserRequest> get() = AskUserRequest::class.java

    override fun execute(request: AskUserRequest, context: ToolContext?): AskUserResponse
}

@Serializable
data class AskUserRequest(
    val stepId: String,
    val question: String,
    val options: List<String> = emptyList(),
    val context: String? = null
)

@Serializable
data class AskUserResponse(
    val status: String,  // "waiting_for_user"
    val message: String
)
