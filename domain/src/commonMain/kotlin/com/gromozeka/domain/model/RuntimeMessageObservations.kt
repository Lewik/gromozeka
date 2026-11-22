package com.gromozeka.domain.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val DURABLE_RUNTIME_OBSERVATION_BOUNDARY_METADATA = "gromozekaDurableRuntimeObservationBoundary"

val Conversation.Message.isDurableRuntimeObservationBoundary: Boolean
    get() = providerMetadata[DURABLE_RUNTIME_OBSERVATION_BOUNDARY_METADATA] == JsonPrimitive(true)

fun Conversation.Message.withDurableRuntimeObservationBoundary(): Conversation.Message = copy(
    providerMetadata = JsonObject(providerMetadata + (DURABLE_RUNTIME_OBSERVATION_BOUNDARY_METADATA to JsonPrimitive(true))),
)

fun Conversation.Message.assistantTextWithRuntimeObservations(text: String): String {
    val observations = instructions.filterIsInstance<Conversation.Message.Instruction.RuntimeObservation>()
    if (observations.isEmpty()) return text
    check(role == Conversation.Message.Role.ASSISTANT && author is Conversation.Message.Author.Runtime) {
        "Assistant runtime observations require Runtime authorship"
    }
    return (observations.map { it.toXmlLine() } + text).filter(String::isNotBlank).joinToString("\n")
}
