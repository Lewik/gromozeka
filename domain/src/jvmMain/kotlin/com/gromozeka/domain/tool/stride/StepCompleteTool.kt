package com.gromozeka.domain.tool.stride

import com.gromozeka.domain.tool.Tool
import kotlinx.serialization.Serializable
import org.springframework.ai.chat.model.ToolContext

/**
 * Domain specification for step completion notification tool.
 *
 * # MCP Tool Exposure
 * **Tool name:** `step_complete`
 * **Exposed via:** Spring AI + MCP protocol
 *
 * # Purpose
 * Signal that current execution step has completed successfully.
 * Provides result summary and optional distillate for thread history.
 *
 * ## Core Features
 *
 * **Step completion:**
 * - Updates Step status to COMPLETED in Neo4j
 * - Stores result summary for graph queries
 * - Sets completedAt timestamp
 *
 * **Distillation trigger:**
 * - If distillate provided, creates SquashOperation
 * - Compresses step execution Messages into single message
 * - Preserves original Messages (immutable audit trail)
 * - New Thread references only distillate
 *
 * **Flow control:**
 * - Signals application to proceed to next step
 * - If no more steps, completes plan and exits stride mode
 *
 * # When to Use
 *
 * **Call after:**
 * - ReAct loop successfully executed command/query
 * - Passthrough step stored to graph
 * - Step verification passed (certainty check)
 *
 * **Do NOT call if:**
 * - Step execution failed → use `step_failed`
 * - Need user input → use `ask_user`
 * - Partial completion → continue execution, call when fully done
 *
 * # Parameters
 *
 * ## stepId: String
 * UUID of completed step (provided by application at step start).
 *
 * ## result: String
 * Brief execution result summary (1-3 sentences).
 * Stored in Step.result for graph queries.
 *
 * Example:
 * - "Found 15 TODO items, grouped by priority: High(3), Medium(7), Low(5)"
 * - "Created config.yaml with default settings"
 * - "Stored fact: project uses PostgreSQL 16"
 *
 * ## distillate: String (optional)
 * Compressed representation of step execution for thread history.
 * If provided, triggers SquashOperation for step Messages.
 *
 * Example:
 * ```
 * Original execution (5 messages):
 * - Thought: need to search codebase
 * - Action: rg "TODO" --type kt
 * - Observation: 15 matches found
 * - Thought: group by priority
 * - Result: [formatted list]
 *
 * Distillate (1 message):
 * "Found 15 TODO items in Kotlin files:
 * - High priority: 3 items (auth, security)
 * - Medium: 7 items (refactoring, tests)
 * - Low: 5 items (cleanup, docs)"
 * ```
 *
 * # Response
 *
 * Returns success confirmation and next action:
 * ```json
 * {
 *   "status": "completed",
 *   "nextStep": {
 *     "id": "next-step-uuid",
 *     "text": "Group them by priority",
 *     "type": "COMMAND"
 *   }
 * }
 * ```
 *
 * Or if plan finished:
 * ```json
 * {
 *   "status": "plan_completed",
 *   "nextStep": null
 * }
 * ```
 *
 * # State Transitions
 *
 * **Step state change:**
 * - Step.status transitions from EXECUTING to COMPLETED
 * - Step.result is set to provided result summary
 * - Step.completedAt is set to current timestamp
 *
 * **Distillation behavior:**
 * - When distillate is provided, Messages created during step execution are compressed
 * - SquashOperation is created linking source Messages to distillate
 * - Source Messages remain immutable (audit trail preservation)
 * - New Thread references distillate only (compressed history)
 *
 * **Next step determination:**
 * - Next PENDING step with satisfied dependencies is identified
 * - If next step exists, it is returned in response
 * - If no more steps exist, plan completion is signaled
 *
 * **Distillation properties:**
 * - Distillate contains RESULT, not execution PROCESS
 * - Sources and citations are preserved
 * - Intermediate tool calls and observations are excluded
 *
 * **Error conditions:**
 * - stepId not found → StepNotFoundException
 * - Step not in EXECUTING state → InvalidStepStateException
 * - Distillation failure → operation continues without distillation
 */
interface StepCompleteTool : Tool<StepCompleteRequest, StepCompleteResponse> {
    override val name: String get() = "step_complete"
    override val description: String get() = "Mark current step as completed and provide result"
    override val requestType: Class<StepCompleteRequest> get() = StepCompleteRequest::class.java

    override fun execute(request: StepCompleteRequest, context: ToolContext?): StepCompleteResponse
}

@Serializable
data class StepCompleteRequest(
    val stepId: String,
    val result: String,
    val distillate: String? = null
)

@Serializable
data class StepCompleteResponse(
    val status: String,  // "completed" | "plan_completed"
    val nextStep: NextStepInfo?
)

@Serializable
data class NextStepInfo(
    val id: String,
    val text: String,
    val type: String
)
