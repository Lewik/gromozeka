package com.gromozeka.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Single step in execution plan (Stride Unit).
 *
 * Step represents minimal semantic fragment with one intent and necessary context.
 * Execution behavior determined by type:
 * - command/query → ReAct loop (full agent execution with tools)
 * - inform/commit/correct/condition/evaluate → passthrough (direct graph storage)
 *
 * Stored in Neo4j as (Step) node with DEPENDS_ON relationships.
 *
 * @property id unique step identifier (UUIDv7)
 * @property planId parent plan
 * @property text original text fragment from user message
 * @property type step type (determines execution behavior)
 * @property certainty confidence level (0.0-1.0): 1.0 = fact, 0.5 = guess, 0.0 = doubt
 * @property entities key entities/technologies mentioned (for graph linking)
 * @property meta arbitrary metadata for specific type (e.g., {"deadline": "tomorrow"} for commit)
 * @property position order in plan (0-based, respects depends_on via topological sort)
 * @property status current execution status
 * @property result execution result summary (brief, for graph queries)
 * @property createdAt timestamp when step was created
 * @property completedAt timestamp when step finished (if completed/failed/invalidated)
 */
@Serializable
data class Step(
    val id: Id,
    val planId: Plan.Id,
    val text: String,
    val type: Type,
    val certainty: Float,
    val entities: List<String>,
    val meta: JsonElement,
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
     * Step type (determines execution behavior).
     *
     * Binary routing:
     * - command/query → ReAct execution
     * - inform/commit/correct/condition/evaluate → passthrough to graph
     *
     * Type taxonomy is future-proofing for more sophisticated execution behavior.
     */
    enum class Type {
        /** Execute action (e.g., "find all TODO", "run tests") → ReAct */
        COMMAND,

        /** Request information/analysis (e.g., "what's better?", "explain") → ReAct */
        QUERY,

        /** State fact/context (e.g., "we use PostgreSQL") → passthrough */
        INFORM,

        /** Promise to do something (e.g., "I'll fix this tomorrow") → passthrough */
        COMMIT,

        /** Correct previous fact (e.g., "no, it's MySQL, not PostgreSQL") → passthrough */
        CORRECT,

        /** Constraint for other steps (e.g., "only if project is Kotlin") → passthrough */
        CONDITION,

        /** Opinion/evaluation (e.g., "this approach is better") → passthrough */
        EVALUATE
    }

    /**
     * Step execution status.
     */
    enum class Status {
        /** Not started yet (waiting for dependencies) */
        PENDING,

        /** Currently running ReAct loop */
        EXECUTING,

        /** Successfully finished */
        COMPLETED,

        /** Execution failed */
        FAILED,

        /** Dependency failed, step is obsolete (cascading invalidation) */
        INVALIDATED
    }
}
