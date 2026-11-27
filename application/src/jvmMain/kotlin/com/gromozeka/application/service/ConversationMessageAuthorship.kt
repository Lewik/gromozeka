package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User

fun Conversation.Message.attributeAuthenticatedSubmission(actorUser: User): Conversation.Message {
    require(providerMetadata["syntheticKind"] != kotlinx.serialization.json.JsonPrimitive("ASYNC_TOOL_COMPLETION") &&
        !providerMetadata.containsKey("asyncToolOperationId")) {
        "Async completion provenance cannot be submitted by clients"
    }
    require(instructions.none { it is Conversation.Message.Instruction.RuntimeObservation }) {
        "Runtime observations cannot be submitted as user instructions"
    }
    if (role == Conversation.Message.Role.RUNTIME) {
        return copy(author = Conversation.Message.Author.Runtime())
    }
    require(author !is Conversation.Message.Author.Runtime) {
        "Runtime authorship requires the Runtime role"
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
