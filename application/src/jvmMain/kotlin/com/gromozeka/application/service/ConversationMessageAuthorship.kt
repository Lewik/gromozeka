package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User

fun Conversation.Message.attributeAuthenticatedSubmission(actorUser: User): Conversation.Message {
    require(role != Conversation.Message.Role.RUNTIME) {
        "Runtime messages cannot be submitted by clients"
    }
    require(author !is Conversation.Message.Author.Runtime) {
        "Runtime-authored messages cannot be submitted by clients"
    }
    require(providerMetadata["syntheticKind"] != kotlinx.serialization.json.JsonPrimitive("ASYNC_TOOL_COMPLETION") &&
        !providerMetadata.containsKey("asyncToolOperationId")) {
        "Async completion provenance cannot be submitted by clients"
    }
    require(instructions.none { it is Conversation.Message.Instruction.RuntimeObservation }) {
        "Runtime observations cannot be submitted as user instructions"
    }
    return copy(
        author = if (instructions.any { it is Conversation.Message.Instruction.Source.Agent }) {
            null
        } else {
            Conversation.Message.Author.User(
                userId = actorUser.id,
                displayName = actorUser.displayName,
            )
        }
    )
}
