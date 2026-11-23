package com.gromozeka.domain.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val DURABLE_RUNTIME_OBSERVATION_BOUNDARY_METADATA = "gromozekaDurableRuntimeObservationBoundary"

val Conversation.Message.isDurableRuntimeObservationMessage: Boolean
    get() = author is Conversation.Message.Author.Runtime &&
        content.isEmpty() &&
        instructions.isNotEmpty() &&
        instructions.all { it is Conversation.Message.Instruction.RuntimeObservation }

private val Conversation.Message.canCarryDurableRuntimeObservationBoundary: Boolean
    get() = isDurableRuntimeObservationMessage ||
        content.singleOrNull() is Conversation.Message.ContentItem.ContextCompactionResult

val Conversation.Message.isDurableRuntimeObservationBoundary: Boolean
    get() = canCarryDurableRuntimeObservationBoundary &&
        providerMetadata[DURABLE_RUNTIME_OBSERVATION_BOUNDARY_METADATA] == JsonPrimitive(true)

fun Conversation.Message.withDurableRuntimeObservationBoundary(): Conversation.Message {
    check(canCarryDurableRuntimeObservationBoundary) {
        "Durable runtime observation boundaries require a Runtime observation or compaction checkpoint"
    }
    return copy(
        providerMetadata = JsonObject(providerMetadata +
            (DURABLE_RUNTIME_OBSERVATION_BOUNDARY_METADATA to JsonPrimitive(true))),
    )
}

/** Keep ordinary transcript content but never replay provider reasoning from before the latest observation baseline. */
fun List<Conversation.Message>.applyDurableRuntimeObservationReplayFence(): List<Conversation.Message> {
    val boundary = indexOfLast { it.isDurableRuntimeObservationBoundary }
    if (boundary <= 0) return this
    return mapIndexed { index, message ->
        if (index < boundary) message.withoutProviderReplay() else message
    }
}

fun Conversation.Message.assistantTextWithRuntimeObservations(text: String): String {
    val observations = instructions.filterIsInstance<Conversation.Message.Instruction.RuntimeObservation>()
    if (observations.isEmpty()) return text
    check(role == Conversation.Message.Role.ASSISTANT && author is Conversation.Message.Author.Runtime) {
        "Assistant runtime observations require Runtime authorship"
    }
    return (observations.map { it.toXmlLine() } + text).filter(String::isNotBlank).joinToString("\n")
}
