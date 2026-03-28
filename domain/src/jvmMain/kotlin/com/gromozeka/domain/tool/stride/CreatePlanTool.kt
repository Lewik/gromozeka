package com.gromozeka.domain.tool.stride

import com.gromozeka.domain.service.StepInput
import com.gromozeka.domain.service.StepRuntime
import com.gromozeka.domain.tool.Tool
import kotlinx.serialization.Serializable
import com.gromozeka.domain.tool.ToolExecutionContext

/**
 * Domain specification for plan creation tool.
 *
 * # MCP Tool Exposure
 * **Tool name:** `create_plan`
 * **Exposed via:** Spring AI + MCP protocol
 *
 * # Purpose
 * Decompose user message into semantic steps (Discourse Units) for execution.
 * Entry point for Stride Engine.
 *
 * # Execution Context
 *
 * **When called:**
 * - State: PLANNING
 * - tool_choice: any
 * - Available tools: [create_plan, request_user_input]
 *
 * **After execution:**
 * - Plan created in Neo4j (status = ACTIVE)
 * - Steps created (status = PENDING)
 * - First step marked IN_PROGRESS
 * - State → STEPPING
 *
 * # Tool Responsibility
 *
 * This tool performs semantic decomposition:
 * - Receives user message text
 * - Returns structured step definitions (JSON)
 * - Does NOT execute steps (LLM does via other tools)
 *
 * Infrastructure creates Plan and Step entities in Neo4j.
 *
 * # Core Features
 *
 * **Semantic decomposition:**
 * - Split message into minimal units with single intent
 * - Detect discourse boundaries (markers, topic changes, speech act shifts)
 * - Resolve anaphora from thread context
 * - Extract entities and certainty levels
 *
 * **Dependency tracking:**
 * - Identify execution dependencies between steps
 * - Support non-contiguous dependencies
 * - Enable execution order determination
 *
 * **Type classification:**
 * - 7 types: command, query, inform, commit, correct, condition, evaluate
 * - Type determines instruction template in tool_result
 *
 * # Parameters
 *
 * ## steps: List<StepInput>
 * Array of step definitions created by LLM.
 *
 * Each step contains:
 * - **text** - original fragment from user message
 * - **type** - step type (command/query/inform/commit/correct/condition/evaluate)
 * - **certainty** - confidence level (0.0-1.0)
 * - **entities** - key entities/technologies mentioned
 * - **depends_on** - array of step indices (0-based)
 *
 * # Response (tool_result from infrastructure)
 *
 * Infrastructure returns unified tool_result format:
 * ```json
 * {
 *   "plan_id": "uuid",
 *   "steps": [
 *     {
 *       "id": 0,
 *       "text": "Find all TODO in project",
 *       "type": "command",
 *       "status": "IN_PROGRESS",
 *       "result": null,
 *       "certainty": 1.0,
 *       "entities": ["TODO", "project"],
 *       "depends_on": []
 *     }
 *   ],
 *   "current_step_id": 0,
 *   "instruction": "Execute task: 'Find all TODO in project'. Gather context, use tools, complete. Call step_complete when done."
 * }
 * ```
 *
 * # Type Guidelines
 *
 * **command** - direct action instruction
 * - "find all TODO", "run tests", "create file"
 * - App instruction: "Execute task: '{text}'. Gather context, use tools..."
 *
 * **query** - request for information/analysis
 * - "what's better?", "should we use X?", "explain why"
 * - App instruction: "Answer question: '{text}'. Research topic..."
 * - NOTE: Question ≠ command ("is this needed?" ≠ "delete this")
 *
 * **inform** - statement of fact/context
 * - "we use PostgreSQL", "bug was in listener"
 * - App instruction: "User states: '{text}'. Related issues? Consequences?..."
 *
 * **commit** - speaker's promise/obligation
 * - "I'll fix this tomorrow", "will write tests by Friday"
 * - App instruction: "User commits: '{text}'. Record. Dependencies?..."
 *
 * **correct** - correction of known fact
 * - "no, it's MySQL not PostgreSQL", "not a bug, it's a feature"
 * - App instruction: "User corrects: '{text}'. What depended on old fact?..."
 *
 * **condition** - constraint for other steps
 * - "if project is Kotlin", "only for Linux"
 * - App instruction: "User sets condition: '{text}'. Is it satisfied? What depends?..."
 *
 * **evaluate** - opinion/assessment
 * - "this approach is better", "Swing is terrible"
 * - App instruction: "User opines: '{text}'. Do you agree? Arguments?..."
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
 * When certainty < 1.0, app adds modifier to instruction:
 * > "Confidence in this statement is low ({certainty}). Gather additional context,
 * > make decision autonomously. Don't ask user — decide yourself."
 *
 * # Dependency Rules
 *
 * **depends_on** - array of step indices (0-based)
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
 * # Discourse Boundaries
 *
 * Split into separate steps when:
 * - **Explicit markers:** "кстати", "а ещё", "но", "также", "ещё"
 * - **Speech act change:** command → inform → query
 * - **Topic/entity change:** TODO → hotkey bug → Compose Desktop
 * - **Temporal frame change:** "сейчас" → "вчера"
 * - **Modality change:** fact → hypothesis
 * - **Addressee change:** "ты делай" → "я сделал"
 *
 * Don't split if single task: "find and group" = one step, not two.
 *
 * # Examples
 *
 * **Single command:**
 * ```
 * Input: "Run tests and show results"
 * Output: [
 *   {
 *     "text": "Run tests and show results",
 *     "type": "command",
 *     "certainty": 1.0,
 *     "entities": ["tests"],
 *     "depends_on": []
 *   }
 * ]
 * ```
 *
 * **Multiple steps with dependency:**
 * ```
 * Input: "Seems like cache issue. Clear it and check"
 * Output: [
 *   {
 *     "text": "Seems like cache issue",
 *     "type": "inform",
 *     "certainty": 0.6,
 *     "entities": ["cache"],
 *     "depends_on": []
 *   },
 *   {
 *     "text": "Clear cache and check",
 *     "type": "command",
 *     "certainty": 1.0,
 *     "entities": ["cache"],
 *     "depends_on": [0]
 *   }
 * ]
 * ```
 *
 * **Complex message:**
 * ```
 * Input: "Find all TODO in project, group by priority. BTW, yesterday's hotkey bug is fixed — problem was in KeyEvent listener. And should we switch to Compose Desktop instead of Swing?"
 * Output: [
 *   {
 *     "text": "Find all TODO in project, group by priority",
 *     "type": "command",
 *     "certainty": 1.0,
 *     "entities": ["TODO", "project"],
 *     "depends_on": []
 *   },
 *   {
 *     "text": "yesterday's hotkey bug is fixed — problem was in KeyEvent listener",
 *     "type": "inform",
 *     "certainty": 1.0,
 *     "entities": ["hotkey-bug", "KeyEvent listener"],
 *     "depends_on": []
 *   },
 *   {
 *     "text": "should we switch to Compose Desktop instead of Swing",
 *     "type": "query",
 *     "certainty": 1.0,
 *     "entities": ["Compose Desktop", "Swing"],
 *     "depends_on": []
 *   }
 * ]
 * ```
 */
interface CreatePlanTool : Tool<CreatePlanRequest, CreatePlanResponse> {
    override val name: String get() = "create_plan"
    override val description: String get() = "Decompose user message into semantic execution steps"
    override val requestType: Class<CreatePlanRequest> get() = CreatePlanRequest::class.java

    override fun execute(request: CreatePlanRequest, context: ToolExecutionContext?): CreatePlanResponse
}

/**
 * Tool request - pure domain type.
 * Infrastructure layer converts to/from Jackson DTOs.
 */
@Serializable
data class CreatePlanRequest(
    val steps: List<StepInput>
)

/**
 * Tool response - pure domain type.
 * Infrastructure layer converts to/from Jackson DTOs.
 */
@Serializable
data class CreatePlanResponse(
    val planId: String,
    val steps: List<StepRuntime>,
    val currentStepId: Int,
    val instruction: String
)
