package com.gromozeka.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

// ════════════════════════════════════════════════════════════════════════════════
// STRIDE ENGINE - EXECUTION PLAN
// ════════════════════════════════════════════════════════════════════════════════
//
// # System Behavior Specification
//
// ## Activation
//
// Stride Engine is active when conversation.strideEnabled = true.
//
// ## Execution Model
//
// Stride Engine operates within the standard LLM interaction loop.
// There is no separate orchestration layer.
//
// ## Tool Call Constraints
//
// **First LLM call invariant:**
// - When strideEnabled = true AND iteration count = 0
// - THEN plan_steps tool MUST be called (tool_choice = REQUIRED)
// - LLM cannot produce text response without calling plan_steps
//
// **Subsequent calls:**
// - tool_choice is unrestricted (LLM selects tools autonomously)
// - LLM follows plan by calling appropriate tools
// - Execution continues while LLM produces tool calls
// - Execution terminates when LLM produces response without tool calls
//
// ## State Transitions
//
// **Plan creation:**
// - plan_steps tool execution creates Plan entity in Neo4j
// - Plan contains ordered Steps with dependency relationships
//
// **Step execution:**
// - LLM executes steps by calling domain tools
// - step_complete tool marks step as done, returns next step
// - step_failed tool marks step as failed, invalidates dependents
//
// **User interaction:**
// - ask_user tool returns text question (not a tool call)
// - LLM incorporates question into response
// - Response without tool calls terminates execution loop
// - User answer initiates new execution cycle
//
// ## Storage Model
//
// - Plan and Step entities persist in Neo4j graph database
// - Entities are separate from Message entities (SQL storage)
// - Connection to Conversation via conversationId property (not relationship)
// - Plans are temporary execution artifacts (lifecycle independent of Messages)
//
// ## Design Invariants
//
// - Control flow complexity resides in LLM behavior (prompts + tools)
// - Application layer provides minimal coordination (tool_choice enforcement)
// - No state machine or workflow engine required
//
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Execution plan created from user message.
 *
 * Plan contains ordered steps with dependencies, created by semantic decomposition
 * of user message through `plan_steps` tool.
 *
 * Plans are temporary execution artifacts:
 * - Created when user message triggers Stride Engine (strideEnabled = true)
 * - Executed step-by-step through ReAct or passthrough
 * - Archived or deleted after completion
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
        EXECUTING,

        /** All steps completed successfully */
        COMPLETED,

        /** Plan failed (step_failed or critical error) */
        FAILED,

        /** User cancelled execution */
        CANCELLED
    }
}
