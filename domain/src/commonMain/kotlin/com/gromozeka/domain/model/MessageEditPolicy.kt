package com.gromozeka.domain.model

fun requireEditableConversationMessage(message: Conversation.Message) {
    require(message.author !is Conversation.Message.Author.Runtime &&
        message.syntheticKind != SyntheticMessageKind.ASYNC_TOOL_COMPLETION) {
        "Runtime-authored service messages cannot be edited by clients"
    }
}
