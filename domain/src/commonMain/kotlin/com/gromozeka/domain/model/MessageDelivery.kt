package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

/** How newly submitted conversation input should relate to an active turn. */
@Serializable
enum class MessageDelivery {
    STEER,
    QUEUE,
}

val liveSteeringInstruction = Conversation.Message.Instruction.UserInstruction(
    id = "mid_turn_steer",
    title = "Live steering update",
    description = "This user message was submitted while the assistant was already working. " +
        "Treat it as additional steering for the active turn and incorporate it at the next safe boundary, " +
        "usually after the current tool result. Do not restart or discard completed work unless the user explicitly asks."
)
