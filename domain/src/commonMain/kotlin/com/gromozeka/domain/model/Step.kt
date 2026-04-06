package com.gromozeka.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Single step in execution plan (Discourse Unit).
 *
 * Step represents minimal semantic fragment with one intent and necessary context.
 *
 * ## Format: Input vs Runtime
 *
 * **Input format (from LLM in create_plan):**
 * ```json
 * {
 *   "text": "Find all TODO in project",
 *   "type": "COMMAND",
 *   "certainty": 1.0,
 *   "entities": ["TODO", "project"],
 *   "depends_on": []
 * }
 * ```
 *
 * LLM creates semantic decomposition, app adds execution state.
 *
 * **Runtime format (in tool_result from app):**
 * ```json
 * {
 *   "id": 0,
 *   "text": "Find all TODO in project",
 *   "type": "COMMAND",
 *   "status": "IN_PROGRESS",
 *   "result": null,
 *   "certainty": 1.0,
 *   "entities": ["TODO", "project"],
 *   "depends_on": []
 * }
 * ```
 *
 * App adds: id, status, result. LLM sees full state in tool_result.
 *
 * ## Step Types
 *
 * 7 types based on speech act taxonomy (Searle, ISO 24617-2):
 *
 * | Type | Purpose | Execution |
 * |------|---------|-----------|
 * | COMMAND | Execute action ("find all TODO") | LLM + tools |
 * | QUERY | Request info/analysis ("what's better?") | LLM + tools |
 * | INFORM | State fact ("we use PostgreSQL") | LLM analyzes |
 * | COMMIT | Promise ("I'll fix tomorrow") | LLM records |
 * | CORRECT | Fix fact ("no, it's MySQL") | LLM updates |
 * | CONDITION | Set constraint ("if Kotlin") | LLM checks |
 * | EVALUATE | Give opinion ("this is better") | LLM analyzes |
 *
 * All steps executed through LLM + tools. Type determines instruction template.
 *
 * Stored in Neo4j as (Step) node with DEPENDS_ON relationships.
 *
 * @property id unique step identifier (UUIDv7)
 * @property planId parent plan
 * @property text original text fragment from user message
 * @property type step type (determines instruction template)
 * @property certainty confidence level (0.0-1.0): 1.0 = fact, 0.5 = guess, 0.0 = doubt
 * @property entities key entities/technologies mentioned (for graph linking)
 * @property meta arbitrary metadata for specific type (e.g., {"deadline": "tomorrow"} for COMMIT)
 * @property position order in plan (0-based, respects depends_on)
 * @property status current execution status
 * @property result execution result (null until completed/failed)
 * @property createdAt timestamp when step was created
 * @property completedAt timestamp when step finished (if completed/failed)
 */
@Serializable
data class Step(
    val id: Id,
    val planId: Plan.Id,
    val text: String,
    val type: Type,
    val certainty: Float,
    val entities: List<String>,
    val position: Int,
    val status: Status,
    val result: String? = null,
    val createdAt: Instant,
    val completedAt: Instant? = null
) {
    /**
     * Unique step identifier (UUIDv7).
     */
    @Serializable
    @JvmInline
    value class Id(val value: String)

    /**
     * Step type (determines instruction template).
     *
     * Based on speech act taxonomy (Searle 1975, ISO 24617-2).
     * Type guides app in forming instruction for LLM in tool_result.
     *
     * Pure domain enum serialized using enum names as-is.
     */
    enum class Type {
        /** Execute action (e.g., "find all TODO", "run tests") */
        COMMAND,

        /** Request information/analysis (e.g., "what's better?", "explain") */
        QUERY,

        /** State fact/context (e.g., "we use PostgreSQL") */
        INFORM,

        /** Promise to do something (e.g., "I'll fix this tomorrow") */
        COMMIT,

        /** Correct previous fact (e.g., "no, it's MySQL, not PostgreSQL") */
        CORRECT,

        /** Constraint for other steps (e.g., "only if project is Kotlin") */
        CONDITION,

        /** Opinion/evaluation (e.g., "this approach is better") */
        EVALUATE
    }

    /**
     * Step execution status.
     *
     * Lifecycle: PENDING → IN_PROGRESS → COMPLETED/FAILED
     */
    enum class Status {
        /** Not started yet (waiting for dependencies) */
        PENDING,

        /** Currently being executed by LLM */
        IN_PROGRESS,

        /** Successfully finished */
        COMPLETED,

        /** Execution failed */
        FAILED
    }
}
