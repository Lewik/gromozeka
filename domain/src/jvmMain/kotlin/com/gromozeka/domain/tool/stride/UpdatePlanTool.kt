package com.gromozeka.domain.tool.stride

import com.gromozeka.domain.service.StepInput
import com.gromozeka.domain.service.StepRuntime
import com.gromozeka.domain.tool.Tool
import kotlinx.serialization.Serializable
import com.gromozeka.domain.tool.ToolExecutionContext

/**
 * Domain specification for plan modification tool.
 *
 * # MCP Tool Exposure
 * **Tool name:** `update_plan`
 * **Exposed via:** Spring AI + MCP protocol
 *
 * # Purpose
 * Modify execution plan on the fly based on new information or user input.
 * Allows LLM to adapt plan during execution.
 *
 * # Execution Context
 *
 * **When called:**
 * - State: STEPPING
 * - Plan: ACTIVE
 * - LLM discovered need to change plan
 *
 * **After execution:**
 * - Updated steps saved to Neo4j
 * - Current step may change
 * - tool_result with updated plan + instruction
 *
 * # When to Use
 *
 * **Call when:**
 * - User interrupts and provides clarification/correction
 * - Discovered better approach during execution
 * - Need to add steps based on findings
 * - Need to remove obsolete steps
 * - Need to change dependencies
 *
 * **Examples:**
 * - User: "Actually, search FIXME too, not just TODO"
 *   → Add entities to step 0, update text
 *
 * - During execution: "Found config.yaml already exists"
 *   → Add step: "Ask user: overwrite or merge?"
 *
 * - User: "Skip the analysis, just show results"
 *   → Remove QUERY step, keep only COMMAND
 *
 * - Discovered dependency: "Step 3 needs result from step 1"
 *   → Add depends_on relationship
 *
 * **Do NOT call if:**
 * - Just completing current step → use `step_complete`
 * - Need user input → use `request_user_input` (then update plan based on answer)
 * - Plan is fine → continue execution
 *
 * # Validation Rules
 *
 * **Cannot modify:**
 * - COMPLETED steps (immutable)
 * - FAILED steps (immutable)
 *
 * **Can modify:**
 * - PENDING steps (fully editable)
 * - IN_PROGRESS step (current step only)
 *
 * **Can add:**
 * - New PENDING steps
 * - Dependencies to COMPLETED steps (new step depends on completed one)
 *
 * **Can remove:**
 * - PENDING steps (if no other steps depend on them)
 *
 * **Cannot create:**
 * - Cycles in dependencies (will be rejected)
 *
 * # Parameters
 *
 * ## steps: List<StepInput>
 * Updated step definitions.
 *
 * Must include:
 * - All IN_PROGRESS steps (unchanged or modified)
 * - All PENDING steps (unchanged, modified, or new)
 * - All COMPLETED/FAILED steps (read-only, for reference)
 *
 * App will:
 * 1. Validate: no modifications to COMPLETED/FAILED
 * 2. Detect changes: added, removed, modified steps
 * 3. Update Neo4j
 * 4. Return tool_result with updated plan
 *
 * # Response (tool_result from infrastructure)
 *
 * **Success:**
 * ```json
 * {
 *   "plan_id": "uuid",
 *   "steps": [...],  // updated runtime state
 *   "current_step_id": 1,
 *   "instruction": "Plan updated. Continue with step [1]: ..."
 * }
 * ```
 *
 * **Validation error:**
 * ```json
 * {
 *   "ok": false,
 *   "error": "Cannot modify completed step 0"
 * }
 * ```
 *
 * # Update Patterns
 *
 * **Add step:**
 * ```json
 * {
 *   "steps": [
 *     {...existing step 0...},
 *     {...existing step 1...},
 *     {
 *       "text": "Ask user: overwrite or merge config?",
 *       "type": "QUERY",
 *       "certainty": 1.0,
 *       "entities": ["config"],
 *       "depends_on": [1]  // depends on step that found existing config
 *     }
 *   ]
 * }
 * ```
 *
 * **Modify PENDING step:**
 * ```json
 * {
 *   "steps": [
 *     {...completed step 0...},
 *     {
 *       "text": "Search TODO and FIXME patterns",  // changed text
 *       "type": "COMMAND",
 *       "certainty": 1.0,
 *       "entities": ["TODO", "FIXME"],  // added entity
 *       "depends_on": []
 *     }
 *   ]
 * }
 * ```
 *
 * **Remove step:**
 * ```json
 * {
 *   "steps": [
 *     {...step 0...},
 *     // step 1 removed (was PENDING, no dependents)
 *     {...step 2...}
 *   ]
 * }
 * ```
 *
 * **Change dependencies:**
 * ```json
 * {
 *   "steps": [
 *     {...step 0...},
 *     {...step 1...},
 *     {
 *       "text": "Create report",
 *       "type": "COMMAND",
 *       "certainty": 1.0,
 *       "entities": ["report"],
 *       "depends_on": [0, 1]  // now depends on both 0 and 1
 *     }
 *   ]
 * }
 * ```
 *
 * # State Transitions
 *
 * **Successful update:**
 * 1. Validate: no modifications to COMPLETED/FAILED
 * 2. Detect changes: compare with current plan
 * 3. Update Neo4j: modify/add/remove steps
 * 4. Update dependencies: rebuild DEPENDS_ON relationships
 * 5. Return tool_result with updated plan
 * 6. Execution continues with current or next step
 *
 * **Validation failure:**
 * 1. Detect invalid modification
 * 2. Return error in tool_result
 * 3. Plan unchanged
 * 4. LLM sees error, can retry with corrected update
 *
 * # Common Scenarios
 *
 * **User clarification during execution:**
 * ```
 * Plan: [0: search TODO (IN_PROGRESS), 1: group by priority (PENDING)]
 * User: "Search FIXME too"
 * LLM: update_plan with modified step 0 text + entities
 * App: updates step 0, returns tool_result
 * LLM: continues execution with updated step
 * ```
 *
 * **Discovery during execution:**
 * ```
 * Plan: [0: create config (IN_PROGRESS)]
 * LLM discovers: config.yaml exists
 * LLM: update_plan, add step: "Ask user: overwrite or merge?"
 * App: adds new step, returns tool_result
 * LLM: step_complete step 0, then executes new step
 * ```
 *
 * **User changes mind:**
 * ```
 * Plan: [0: search (COMPLETED), 1: analyze (PENDING), 2: report (PENDING)]
 * User: "Skip analysis, just show results"
 * LLM: update_plan, remove step 1
 * App: removes step 1, returns tool_result
 * LLM: continues with step 2
 * ```
 *
 * # Error Handling
 *
 * **Attempt to modify COMPLETED step:**
 * ```
 * Error: "Cannot modify completed step 0. Completed steps are immutable."
 * ```
 *
 * **Attempt to create cycle:**
 * ```
 * Error: "Cyclic dependency detected: step 2 → step 3 → step 2"
 * ```
 *
 * **Attempt to remove step with dependents:**
 * ```
 * Error: "Cannot remove step 1: step 2 depends on it"
 * ```
 *
 * LLM sees error and can retry with corrected update.
 */
interface UpdatePlanTool : Tool<UpdatePlanRequest, UpdatePlanResponse> {
    override val name: String get() = "update_plan"
    override val description: String get() = "Modify execution plan (add/remove/change steps)"
    override val requestType: Class<UpdatePlanRequest> get() = UpdatePlanRequest::class.java

    override fun execute(request: UpdatePlanRequest, context: ToolExecutionContext?): UpdatePlanResponse
}

/**
 * Tool request - pure domain type.
 * Infrastructure layer converts to/from Jackson DTOs.
 */
@Serializable
data class UpdatePlanRequest(
    val steps: List<StepInput>
)

/**
 * Tool response - pure domain type.
 * Infrastructure layer converts to/from Jackson DTOs.
 */
@Serializable
data class UpdatePlanResponse(
    val ok: Boolean = true,
    val error: String? = null,
    val planId: String? = null,
    val steps: List<StepRuntime>? = null,
    val currentStepId: Int? = null,
    val instruction: String? = null
)
