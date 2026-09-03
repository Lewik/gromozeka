package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User

fun Conversation.Message.attributeAuthenticatedSubmission(actorUser: User): Conversation.Message =
    copy(
        author = if (instructions.any { it is Conversation.Message.Instruction.Source.Agent }) {
            null
        } else {
            Conversation.Message.Author.User(
                userId = actorUser.id,
                displayName = actorUser.displayName,
            )
        }
    )
