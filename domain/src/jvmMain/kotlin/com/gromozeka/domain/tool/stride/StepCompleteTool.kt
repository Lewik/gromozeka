package com.gromozeka.domain.tool.stride

import com.gromozeka.domain.service.StepRuntime
import com.gromozeka.domain.tool.Tool
import kotlinx.serialization.Serializable
import org.springframework.ai.chat.model.ToolContext

/**
 * Domain specification for step completion tool.
 *
 * # MCP Tool Exposure
 * **Tool name:** `step_complete`
 * **Exposed via:** Spring AI + MCP protocol
 *
 * # Purpose
 * Signal that current step has completed (successfully or with failure).
 * Provides result and triggers transition to next step or plan completion.
 *
 * # Execution Context
 *
 * **When called:**
 * - State: STEPPING
 * - Current step: IN_PROGRESS
 * - LLM finished working on step
 *
 * **After execution (success):**
 * - Step → COMPLETED
 * - Result saved
 * - Next step selected (if exists)
 * - tool_result with next step or summary instruction
 *
 * **After execution (failure):**
 * - Step → FAILED
 * - Plan → FAILED
 * - tool_result with instruction to explain error
 * - State → IDLE (after explanation)
 *
 * # When to Use
 *
 * **Call after:**
 * - Step execution finished (all tools called, work done)
 * - Got final answer for query
 * - Completed command execution
 * - Processed inform/commit/correct/condition/evaluate
 *
 * **Success examples:**
 * - "Found 15 TODO items, grouped by priority"
 * - "Created config.yaml with default settings"
 * - "Fact recorded: project uses PostgreSQL 16"
 * - "Analyzed: Compose Desktop better for cross-platform, but Swing more mature"
 *
 * **Failure examples:**
 * - "Could not find TODO comments. Pattern 'TODO' returned no matches."
 * - "File config.yaml already exists with different settings. Cannot overwrite without confirmation."
 * - "Max iterations exceeded. Could not determine correct database."
 *
 * **Do NOT call if:**
 * - Need user input → use `request_user_input`
 * - Want to modify plan → use `update_plan`
 * - Just progress update → use `notify`
 * - Partial completion → continue execution
 *
 * # Parameters
 *
 * ## status: String
 * Completion status: "success" or "fail"
 *
 * - **"success"** - step completed successfully
 * - **"fail"** - step failed, cannot complete
 *
 * ## result: String
 * Execution result (1-3 sentences).
 *
 * For success: what was accomplished, key findings, decisions made.
 * For failure: what went wrong, why it failed, what was attempted.
 *
 * This is stored in Step.result in Neo4j.
 *
 * # Response (tool_result from infrastructure)
 *
 * **Success with more steps:**
 * ```json
 * {
 *   "plan_id": "uuid",
 *   "steps": [...],
 *   "current_step_id": 2,
 *   "instruction": "Execute task: 'Group them by priority'. Previous step found 15 TODO items. ..."
 * }
 * ```
 *
 * **Success, all done:**
 * ```json
 * {
 *   "plan_id": "uuid",
 *   "steps": [...],
 *   "current_step_id": null,
 *   "instruction": "All steps completed. Summarize what was accomplished."
 * }
 * ```
 *
 * **Failure:**
 * ```json
 * {
 *   "plan_id": "uuid",
 *   "steps": [...],
 *   "current_step_id": null,
 *   "instruction": "Step failed: 'Could not find TODO comments...'. Explain to user what happened and why."
 * }
 * ```
 *
 * # State Transitions
 *
 * **Success path:**
 * 1. Step.status → COMPLETED
 * 2. Step.result = result parameter
 * 3. Step.completedAt = now
 * 4. Select next PENDING step with satisfied dependencies
 * 5. Next step → IN_PROGRESS
 * 6. Return tool_result with next step + instruction
 *
 * **All steps done:**
 * 1. Step.status → COMPLETED
 * 2. Plan.status → COMPLETED
 * 3. Plan.completedAt = now
 * 4. Return tool_result with summary instruction
 * 5. LLM writes summary → State → IDLE
 *
 * **Failure path:**
 * 1. Step.status → FAILED
 * 2. Step.result = error description
 * 3. Step.completedAt = now
 * 4. Plan.status → FAILED
 * 5. Plan.completedAt = now
 * 6. Return tool_result with explanation instruction
 * 7. LLM explains error → State → IDLE
 *
 * # Next Step Selection
 *
 * ```
 * next = steps
 *   .filter(status == PENDING)
 *   .filter(all depends_on are COMPLETED)
 *   .first()
 * ```
 *
 * If null and PENDING steps exist → deadlock (unresolved dependencies).
 * App detects this and includes in instruction: "Dependency deadlock detected."
 *
 * # Result Guidelines
 *
 * **Be specific:**
 * - Include key numbers/names ("Found 15 TODO items")
 * - Mention decisions made ("Chose PostgreSQL over MySQL")
 * - State consequences ("File created, tests will need update")
 *
 * **Be concise:**
 * - 1-3 sentences max
 * - Focus on outcome, not process
 * - Skip intermediate steps
 *
 * **Success examples:**
 * - "Found 15 TODO items in 8 files. Grouped by priority: High(3), Medium(7), Low(5)."
 * - "Created config.yaml with PostgreSQL settings. Database connection configured."
 * - "Analyzed: Compose Desktop better for multiplatform, but requires Kotlin migration. Swing more mature, Java-based."
 *
 * **Failure examples:**
 * - "TODO search failed: pattern 'TODO' returned no matches in project. Project may not use TODO markers."
 * - "Cannot create config.yaml: file exists with different database (MySQL). Need user decision."
 * - "Verification failed: graph shows MySQL, step claims PostgreSQL. Conflict unresolved."
 *
 * # Tool Call Discipline
 *
 * **IMPORTANT:** Call step_complete ONLY after processing all tool results.
 *
 * ❌ Wrong:
 * ```
 * 1. Call bash tool
 * 2. Call step_complete (before seeing bash result!)
 * ```
 *
 * ✅ Correct:
 * ```
 * 1. Call bash tool
 * 2. Receive bash result
 * 3. Process result
 * 4. Call step_complete with findings
 * ```
 *
 * Cannot call step_complete in parallel with other tools.
 * It must be the LAST tool call in iteration.
 */
interface StepCompleteTool : Tool<StepCompleteRequest, StepCompleteResponse> {
    override val name: String get() = "step_complete"
    override val description: String get() = "Mark current step as completed (success or failure)"
    override val requestType: Class<StepCompleteRequest> get() = StepCompleteRequest::class.java

    override fun execute(request: StepCompleteRequest, context: ToolContext?): StepCompleteResponse
}

@Serializable
data class StepCompleteRequest(
    val status: String,  // "success" | "fail"
    val result: String
)

@Serializable
data class StepCompleteResponse(
    val planId: String,
    val steps: List<StepRuntime>,
    val currentStepId: Int?,
    val instruction: String
)
