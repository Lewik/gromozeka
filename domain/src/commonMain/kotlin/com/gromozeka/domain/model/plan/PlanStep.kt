package com.gromozeka.domain.model.plan

import com.gromozeka.shared.uuid.uuid7
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PlanStep - one step in a task execution plan.
 *
 * Steps are organized in a tree structure via parentId, which allows:
 * - Creating hierarchical plans (task → subtasks → actions)
 * - Grouping related steps
 * - Building DAG of dependencies (future)
 *
 * Each step type defines:
 * - What will be done by the application during execution (instruction/command/etc)
 * - What result is expected (result/output/etc)
 * - How the step will be executed (manually by LLM / automatically by application)
 *
 * Sealed class ensures type safety and allows each type to have
 * specific fields without generic nullable properties.
 */
@Serializable
sealed class PlanStep {
    abstract val id: Id
    abstract val planId: Plan.Id
    abstract val parentId: Id?
    abstract val status: StepStatus
    
    /**
     * Returns text for vectorization and semantic search.
     * Each step type defines what exactly to vectorize.
     */
    abstract fun getTextForVectorization(): String
    
    /**
     * Text step - basic step type with text instruction.
     *
     * During execution:
     * - Application displays instruction to LLM
     * - LLM executes instruction manually (via MCP tools, code, analysis)
     * - LLM records result via update_step
     *
     * Used for:
     * - Tasks requiring reasoning
     * - Analysis and decision making
     * - Working with code via MCP
     *
     * @property instruction instruction text - what needs to be done at this step
     * @property result execution result - what was achieved (filled by LLM)
     */
    @Serializable
    @SerialName("text")
    data class Text(
        override val id: Id,
        override val planId: Plan.Id,
        override val parentId: Id?,
        override val status: StepStatus,
        val instruction: String,
        val result: String?
    ) : PlanStep() {
        override fun getTextForVectorization(): String = instruction
    }
    
    // TODO: Add in future iterations:
    // - Bash step (automatic command execution)
    // - FileEdit step (automatic change application)
    // - LlmPrompt step (automatic LLM invocation with prompt)
    // - ApiCall step (automatic HTTP request)
    
    /**
     * Typed plan step identifier.
     */
    @Serializable
    @JvmInline
    value class Id(val value: String) {
        companion object {
            fun generate(): Id = Id(uuid7())
        }
    }
}

/**
 * Step execution status.
 *
 * Step lifecycle:
 * PENDING -> DONE (successful execution)
 * PENDING -> SKIPPED (skipped by user)
 * PENDING -> FAILED (failed, requires intervention) - future
 * PENDING -> BLOCKED (waiting for dependencies) - future
 */
@Serializable
enum class StepStatus {
    /**
     * Step is waiting for execution.
     */
    PENDING,
    
    /**
     * Step successfully executed.
     */
    DONE,
    
    /**
     * Step skipped (user decided not to execute).
     */
    SKIPPED
    
    // TODO: Add in future iterations:
    // FAILED,  // Execution failed
    // BLOCKED, // Waiting for dependencies
    // RUNNING  // In progress (for long-running tasks)
}
