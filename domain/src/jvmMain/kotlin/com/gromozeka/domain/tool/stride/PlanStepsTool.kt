package com.gromozeka.domain.tool.stride

import com.gromozeka.domain.tool.Tool
import kotlinx.serialization.Serializable
import org.springframework.ai.chat.model.ToolContext

/**
 * Domain specification for semantic message decomposition tool.
 *
 * # MCP Tool Exposure
 * **Tool name:** `plan_steps`
 * **Exposed via:** Spring AI + MCP protocol
 *
 * # Purpose
 * Decompose user message into semantic steps (Discourse Units) for execution.
 * This is the entry point for Stride Engine - forced tool call when strideEnabled = true.
 *
 * # Execution Constraints
 *
 * **Mandatory first call:**
 * - When conversation.strideEnabled = true AND LLM iteration = 0
 * - THEN this tool MUST be called (tool_choice = REQUIRED)
 * - LLM cannot produce text response without calling this tool
 * - This constraint is enforced by conversation engine
 *
 * **Subsequent behavior:**
 * - After plan_steps completes, tool_choice becomes unrestricted
 * - LLM autonomously executes plan by calling appropriate tools
 * - Execution continues while LLM produces tool calls
 * - Execution terminates when LLM response contains no tool calls
 *
 * ## Tool Responsibility
 *
 * This tool performs semantic decomposition only:
 * - Receives user message text
 * - Returns structured step definitions (JSON)
 * - Does NOT create Plan entity (infrastructure responsibility)
 * - Does NOT execute steps (LLM responsibility)
 *
 * ## Post-Execution State
 *
 * After this tool completes:
 * - Plan entity exists in Neo4j with status = EXECUTING
 * - Step entities exist with status = PENDING
 * - Dependency relationships (DEPENDS_ON) are established
 * - LLM receives confirmation and first step description
 *
 * ## Core Features
 *
 * **Semantic decomposition:**
 * - Split message into minimal units with single intent
 * - Detect discourse boundaries (markers, topic changes, speech act shifts)
 * - Resolve anaphora from thread context
 * - Extract entities and certainty levels
 *
 * **Dependency tracking:**
 * - Identify execution dependencies between steps
 * - Support for non-contiguous dependencies
 * - Enable topological sort for execution order
 *
 * **Type classification:**
 * - 7 types: command, query, inform, commit, correct, condition, evaluate
 * - Binary routing: command/query → ReAct, others → passthrough
 * - Future-proof for sophisticated execution behavior
 *
 * # When to Use
 *
 * **Forced call when:**
 * - User sends message AND conversation.strideEnabled = true
 * - Tool choice: REQUIRED (no option for direct text response)
 *
 * **Returns:**
 * - JSON array of step definitions
 * - Application creates Plan and Steps in Neo4j
 * - Execution begins with first step
 *
 * # Parameters
 *
 * ## message: String
 * User message to decompose into steps.
 * Full message text, not just last line.
 *
 * ## threadContext: String (optional)
 * Recent thread history for anaphora resolution.
 * Last 5-10 messages for context.
 *
 * # Response Structure
 *
 * Returns array of step definitions:
 * ```json
 * [
 *   {
 *     "text": "Find all TODO in project gromozeka",
 *     "type": "COMMAND",
 *     "certainty": 1.0,
 *     "entities": ["TODO", "gromozeka"],
 *     "dependsOn": [],
 *     "meta": {}
 *   },
 *   {
 *     "text": "Yesterday's hotkey bug is fixed - problem was in KeyEvent listener",
 *     "type": "INFORM",
 *     "certainty": 1.0,
 *     "entities": ["hotkey-bug", "KeyEvent listener"],
 *     "dependsOn": [],
 *     "meta": {}
 *   }
 * ]
 * ```
 *
 * # Type Guidelines
 *
 * **COMMAND** - direct action instruction
 * - "find all TODO", "run tests", "create file"
 * - Execution: ReAct loop with tools
 *
 * **QUERY** - request for information/analysis
 * - "what's better?", "should we use X?", "explain why"
 * - Execution: ReAct loop with research
 * - NOTE: Question ≠ command (don't confuse "is this needed?" with "delete this")
 *
 * **INFORM** - statement of fact/context
 * - "we use PostgreSQL", "bug was in listener"
 * - Execution: passthrough to graph storage
 *
 * **COMMIT** - speaker's promise/obligation
 * - "I'll fix this tomorrow", "will write tests by Friday"
 * - Execution: passthrough with deadline tracking
 *
 * **CORRECT** - correction of known fact
 * - "no, it's MySQL not PostgreSQL", "not a bug, it's a feature"
 * - Execution: passthrough with graph invalidation
 *
 * **CONDITION** - constraint for other steps
 * - "if project is Kotlin", "only for Linux"
 * - Execution: passthrough as constraint on dependent steps
 *
 * **EVALUATE** - opinion/assessment
 * - "this approach is better", "Swing is terrible"
 * - Execution: passthrough as subjective knowledge
 *
 * # Certainty Levels
 *
 * - **1.0** - definite fact ("we use PostgreSQL")
 * - **0.8** - high confidence ("probably the issue")
 * - **0.5** - guess ("maybe we should try X")
 * - **0.2** - doubt ("not sure if this helps")
 * - **0.0** - speculation ("could it be Y?")
 *
 * Markers: "точно", "кажется", "может быть", "наверное", "я думаю"
 *
 * # Dependency Rules
 *
 * **dependsOn** - array of step indices (0-based)
 *
 * Add dependency when:
 * - Step uses result from previous step
 * - Step references fact from previous step
 * - Anaphora resolution (pronoun refers to earlier entity)
 * - Temporal sequence ("after that", "then")
 *
 * Example:
 * ```
 * 0: "Find all TODO files" (no deps)
 * 1: "Group them by priority" (depends on 0 - needs TODO list)
 * 2: "The bug is in listener" (no deps - independent fact)
 * 3: "Fix it" (depends on 2 - "it" = listener bug)
 * ```
 *
 * # Implementation Notes
 *
 * **LLM prompt strategy:**
 * - Include type definitions and examples
 * - Provide thread context for anaphora resolution
 * - Request JSON only (no explanatory text)
 * - Validate JSON schema before returning
 *
 * **Error handling:**
 * - Invalid JSON → retry with schema reminder
 * - Missing required fields → use defaults (certainty=1.0, meta={})
 * - Cyclic dependencies → detect and break cycle (or ask user)
 */
interface PlanStepsTool : Tool<PlanStepsRequest, PlanStepsResponse> {
    override val name: String get() = "plan_steps"
    override val description: String get() = "Decompose user message into semantic execution steps"
    override val requestType: Class<PlanStepsRequest> get() = PlanStepsRequest::class.java

    override fun execute(request: PlanStepsRequest, context: ToolContext?): PlanStepsResponse
}

@Serializable
data class PlanStepsRequest(
    val message: String,
    val threadContext: String? = null
)

@Serializable
data class PlanStepsResponse(
    val steps: List<StepDefinitionJson>
)

@Serializable
data class StepDefinitionJson(
    val text: String,
    val type: String,  // "COMMAND" | "QUERY" | "INFORM" | "COMMIT" | "CORRECT" | "CONDITION" | "EVALUATE"
    val certainty: Float,
    val entities: List<String>,
    val dependsOn: List<Int>,
    val meta: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()
)
