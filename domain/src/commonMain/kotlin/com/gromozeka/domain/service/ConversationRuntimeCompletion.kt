package com.gromozeka.domain.service

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Durable terminal fact for agent work, independent of the current queue and retained tool problems. */
@Serializable
data class ConversationRuntimeCompletion(
    val turnId: ConversationRuntimeTurnId,
    val taskId: ConversationRuntimeTask.Id,
    val completedAt: Instant,
    val status: Status,
    val reason: Reason? = null,
    val errorType: String? = null,
    val outcomeUnknown: Boolean = false,
    val problemCount: Int = 0,
) {
    init {
        require(problemCount >= 0)
        require(status != Status.FAILED || reason != null) { "A failed completion requires an explicit reason" }
    }

    @Serializable
    enum class Status { COMPLETED, FAILED, STOPPED, INTERRUPTED, CANCELLED, UNKNOWN }

    @Serializable
    enum class Reason(val summary: String) {
        MODEL_ERROR("The model request failed."),
        MODEL_RESPONSE_INCOMPLETE("The model returned an incomplete or failed response; no actions from that response were executed."),
        MODEL_REFUSED("The model refused the request."),
        ITERATION_LIMIT("The agent reached the tool-loop iteration limit."),
        DELIVERY_FAILED("The runtime task could not start."),
        EXECUTION_OUTCOME_UNKNOWN("Task execution ended without a confirmed result; effects may already have occurred."),
        COLLABORATION_CANCELLED("The collaboration request was cancelled before invocation."),
        ABORTED("Execution was aborted without a recorded user stop or interrupt."),
    }
}
