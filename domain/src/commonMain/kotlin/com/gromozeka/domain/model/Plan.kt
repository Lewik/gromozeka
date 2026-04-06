package com.gromozeka.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

// ════════════════════════════════════════════════════════════════════════════════
// STRIDE ENGINE - EXECUTION PLAN
// ════════════════════════════════════════════════════════════════════════════════
//
// # Stride Engine Architecture
//
// Stride Engine decomposes user messages into semantic steps and executes them
// autonomously. Control flow is managed through tool_result responses, not through
// separate orchestration layer.
//
// ## Core Principle
//
// > "No separate orchestrator framework needed — complexity resides in prompts and
// > tools, not in control flow."
//
// Application runs standard LLM interaction loop (while loop with tool calls).
// State machine tracks current mode, but actual execution control happens through
// tool_result content.
//
// ## State Machine
//
// ```
// IDLE ──user msg──→ PLANNING ──create_plan──→ STEPPING ──all done──→ IDLE
//                                                ↑    │
//                                                └────┘ step_complete + more steps
// ```
//
// | State    | tool_choice | Available Tools |
// |----------|-------------|-----------------|
// | IDLE     | auto        | all (normal chat) |
// | PLANNING | any         | create_plan, request_user_input |
// | STEPPING | any         | step_complete, update_plan, request_user_input, notify, + all MCP tools |
//
// State reflects what we're doing. Real state is in the Plan entity.
//
// ## Execution Control via tool_result
//
// App manages LLM through tool_result responses. When LLM calls create_plan,
// step_complete, or update_plan — app returns tool_result with updated plan state
// and instruction for next action.
//
// **Unified tool_result format:**
// ```json
// {
//   "plan_id": "uuid",
//   "steps": [
//     {
//       "id": 0,
//       "text": "Find all TODO...",
//       "type": "COMMAND",
//       "status": "COMPLETED",
//       "result": "Found 49 TODO items...",
//       "certainty": 1.0,
//       "entities": ["TODO", "gromozeka"],
//       "depends_on": []
//     },
//     {
//       "id": 1,
//       "text": "Yesterday's hotkey bug...",
//       "type": "INFORM",
//       "status": "IN_PROGRESS",
//       "result": null,
//       "certainty": 1.0,
//       "entities": ["hotkey-bug"],
//       "depends_on": []
//     }
//   ],
//   "current_step_id": 1,
//   "instruction": "Current step — [1] INFORM: 'Yesterday's hotkey bug...'. User states fact. Process: ..."
// }
// ```
//
// LLM sees this tool_result and follows instruction. App forms instruction based
// on step type and status.
//
// ## Workflow
//
// **Planning:**
// 1. User sends message
// 2. App checks: active plan exists? No → PLANNING state
// 3. App calls LLM with tool_choice: any, tools: [create_plan, request_user_input]
// 4. LLM calls create_plan({ steps: [...] })
// 5. App saves plan in Neo4j, selects first step, marks IN_PROGRESS
// 6. App returns tool_result: { plan, current_step_id, instruction }
// 7. → STEPPING state
//
// **Execution loop:**
// 1. LLM receives tool_result with plan + current step + instruction
// 2. LLM works: calls tools (bash, web, MCP...) as needed
// 3. LLM calls step_complete({ status: "success", result: "..." })
// 4. App: step → COMPLETED, saves result
// 5. App selects next PENDING step (first with resolved depends_on)
// 6. Has next step? → tool_result with new step → goto 1
// 7. No more steps? → tool_result with instruction: "Summarize" → LLM writes summary → IDLE
//
// **Failure:**
// 1. LLM calls step_complete({ status: "fail", result: "error reason" })
// 2. App: step → FAILED, plan → FAILED
// 3. App returns tool_result with instruction: "Explain error to user"
// 4. LLM writes explanation → IDLE
//
// **User interruption:**
// 1. User writes message while LLM works
// 2. App cancels HTTP request
// 3. Step remains IN_PROGRESS, plan ACTIVE
// 4. User message added to context
// 5. LLM sees plan from previous tool_result in history
// 6. LLM decides: continue step, update_plan, request_user_input, or step_complete
//
// ## Instructions by Step Type
//
// App forms instruction in tool_result based on step type:
//
// | Type | Instruction Template |
// |------|---------------------|
// | COMMAND | Execute task: '{text}'. Gather context, use tools, complete. Call step_complete when done. |
// | QUERY | Answer question: '{text}'. Research topic, gather information. Call step_complete with detailed answer. |
// | INFORM | User states: '{text}'. Are there related issues or consequences? Anything to update? Call step_complete with findings. |
// | CORRECT | User corrects fact: '{text}'. What depended on old fact? What to reconsider? Call step_complete with analysis. |
// | EVALUATE | User gives opinion: '{text}'. Do you agree? Arguments for/against? Call step_complete with analysis. |
// | COMMIT | User makes commitment: '{text}'. Record it. Any dependencies or conditions? Call step_complete. |
// | CONDITION | User sets condition: '{text}'. Is it satisfied now? What depends on it? Call step_complete. |
//
// **Certainty modifier** (added when certainty < 1.0):
// > "Confidence in this statement is low ({certainty}). Gather additional context,
// > make decision autonomously and record in result. Don't ask user — decide yourself
// > based on available information."
//
// ## Next Step Selection
//
// ```
// next = steps.filter(PENDING).filter(all depends_on are COMPLETED).first()
// ```
//
// If null and PENDING steps exist → deadlock (unresolved dependencies) → step_complete(fail).
//
// ## Storage Model
//
// - Plan and Step entities persist in Neo4j graph database
// - Entities separate from Message entities (SQL storage)
// - Connection to Conversation via conversationId property (not relationship)
// - Plans are temporary execution artifacts (lifecycle independent of Messages)
//
// ## Design Invariants
//
// - Control flow complexity resides in LLM behavior (prompts + tools)
// - Application provides minimal coordination (tool_result formation)
// - No state machine or workflow engine required
// - Plan state IS the execution state
//
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Execution plan created from user message.
 *
 * Plan contains ordered steps with dependencies, created by semantic decomposition
 * of user message through `create_plan` tool.
 *
 * Plans are temporary execution artifacts:
 * - Created when user message triggers Stride Engine (conversation.strideEnabled = true)
 * - Executed step-by-step through tool calls
 * - Completed or failed when all steps processed
 *
 * Stored in Neo4j as (Plan) node with HAS_STEP relationships.
 *
 * @property id unique plan identifier (UUIDv7)
 * @property conversationId conversation this plan belongs to
 * @property status current execution status
 * @property createdAt timestamp when plan was created
 * @property completedAt timestamp when plan finished (if completed/failed/cancelled)
 */
@Serializable
data class Plan(
    val id: Id,
    val conversationId: Conversation.Id,
    val status: Status,
    val createdAt: Instant,
    val completedAt: Instant? = null
) {
    /**
     * Unique plan identifier (UUIDv7).
     */
    @Serializable
    @JvmInline
    value class Id(val value: String)

    /**
     * Plan execution status.
     */
    enum class Status {
        /** Plan is currently being executed */
        ACTIVE,

        /** All steps completed successfully */
        COMPLETED,

        /** Plan failed (step failed or critical error) */
        FAILED,

        /** User cancelled execution */
        CANCELLED
    }
}
