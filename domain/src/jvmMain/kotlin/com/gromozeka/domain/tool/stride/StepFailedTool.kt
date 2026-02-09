package com.gromozeka.domain.tool.stride

import com.gromozeka.domain.tool.Tool
import kotlinx.serialization.Serializable
import org.springframework.ai.chat.model.ToolContext

/**
 * Domain specification for step failure notification tool.
 *
 * # MCP Tool Exposure
 * **Tool name:** `step_failed`
 * **Exposed via:** Spring AI + MCP protocol
 *
 * # Purpose
 * Signal that current execution step has failed and cannot be completed.
 * Returns error text → LLM includes in response → loop exits automatically.
 *
 * ## Core Features
 *
 * **Step failure:**
 * - Updates Step status to FAILED in Neo4j
 * - Stores error reason
 * - Sets completedAt timestamp
 *
 * **Cascading invalidation:**
 * - Finds all transitive dependents via DEPENDS_ON graph
 * - Marks them as INVALIDATED
 * - Returns formatted error report
 *
 * **Automatic loop exit:**
 * - Tool returns error text (NOT a tool call)
 * - LLM includes error in response
 * - Response has no tool calls → while loop exits
 * - User sees error, decides next action
 * - Can send new message to retry/replan/cancel
 *
 * # When to Use
 *
 * **Call when:**
 * - ReAct loop exhausted max iterations without success
 * - Tool execution fails critically (file not found, permission denied)
 * - Certainty verification fails (fact contradicts known data)
 * - Logical impossibility detected (contradictory constraints)
 *
 * **Do NOT call if:**
 * - Need user input to continue → use `ask_user`
 * - Partial success achieved → use `step_complete` with partial result
 * - Recoverable error → retry within ReAct loop
 *
 * # Parameters
 *
 * ## stepId: String
 * UUID of failed step (provided by application at step start).
 *
 * ## error: String
 * Failure reason explanation (2-4 sentences).
 * Should be user-readable and actionable.
 *
 * Example:
 * - "Could not find TODO comments in project. Searched all .kt files but pattern 'TODO' returned no matches. Project may not use TODO markers."
 * - "File config.yaml already exists and contains different settings. Cannot overwrite without user confirmation."
 * - "Fact verification failed: graph shows project uses MySQL, but step claims PostgreSQL. Invalidating dependent database migration steps."
 *
 * ## retryable: Boolean (default: false)
 * Whether user can retry this step with same parameters.
 *
 * - `true` - transient failure (network, timeout, resource unavailable)
 * - `false` - permanent failure (logical error, missing prerequisite)
 *
 * # Response
 *
 * Returns failure details and invalidated steps:
 * ```json
 * {
 *   "status": "failed",
 *   "invalidatedSteps": [
 *     {
 *       "id": "step-uuid-2",
 *       "text": "Group them by priority",
 *       "reason": "Depends on failed TODO search"
 *     },
 *     {
 *       "id": "step-uuid-3",
 *       "text": "Create report",
 *       "reason": "Depends on TODO grouping"
 *     }
 *   ],
 *   "retryable": false
 * }
 * ```
 *
 * # State Transitions
 *
 * **Failed step:**
 * - Step.status transitions to FAILED
 * - Step.result contains error description
 * - Step.completedAt is set to current timestamp
 *
 * **Cascading invalidation:**
 * - All transitive dependents (via DEPENDS_ON relationships) are identified
 * - Dependent steps in PENDING or EXECUTING state transition to INVALIDATED
 * - Invalidated steps are included in tool response
 *
 * **Execution termination:**
 * - Tool returns error text (not a tool call)
 * - LLM incorporates error into response without further tool calls
 * - Execution loop terminates naturally (no tool calls present)
 *
 * **Dependency graph traversal:**
 * - Transitive closure of DEPENDS_ON relationships is computed
 * - Only steps depending (directly or indirectly) on failed step are affected
 * - Steps without dependency path remain unaffected
 *
 * **Recovery options:**
 * - retryable flag indicates whether failure is transient
 * - Transient failures (network, timeout) are marked retryable
 * - Permanent failures (logic errors, missing prerequisites) are not retryable
 *
 * **Error categories:**
 * - Transient: network errors, timeouts, resource unavailability
 * - Prerequisite: missing data, unmet conditions
 * - Logical: contradictions, impossible constraints
 * - Resource: max iterations exceeded, quota limits
 */
interface StepFailedTool : Tool<StepFailedRequest, StepFailedResponse> {
    override val name: String get() = "step_failed"
    override val description: String get() = "Mark current step as failed and trigger invalidation cascade"
    override val requestType: Class<StepFailedRequest> get() = StepFailedRequest::class.java

    override fun execute(request: StepFailedRequest, context: ToolContext?): StepFailedResponse
}

@Serializable
data class StepFailedRequest(
    val stepId: String,
    val error: String,
    val retryable: Boolean = false
)

@Serializable
data class StepFailedResponse(
    val status: String,  // "failed"
    val invalidatedSteps: List<InvalidatedStepInfo>,
    val retryable: Boolean
)

@Serializable
data class InvalidatedStepInfo(
    val id: String,
    val text: String,
    val reason: String
)
