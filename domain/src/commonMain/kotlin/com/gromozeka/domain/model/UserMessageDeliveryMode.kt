package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

/** Timing of newly submitted human input; never a cancellation policy. */
@Serializable
enum class UserMessageDeliveryMode {
    STEER,
    AFTER_CURRENT_TURN,
}

val liveSteeringInstruction = Conversation.Message.Instruction.UserInstruction(
    id = "mid_turn_steer",
    title = "Live steering update",
    description = "This user message was submitted while the assistant was already working. " +
        "Treat it as additional steering for the active turn and incorporate it at the next safe boundary, " +
        "usually after the current tool result. Do not restart or discard completed work unless the user explicitly asks."
)
