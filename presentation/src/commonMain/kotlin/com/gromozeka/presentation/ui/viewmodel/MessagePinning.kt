package com.gromozeka.presentation.ui.viewmodel

import com.gromozeka.domain.model.Conversation

/** Copy only readable message text. Never serialize content items, instructions or native payloads. */
internal fun Conversation.Message.pinTextSnapshot(): String? = content.mapNotNull { item ->
    when (item) {
        is Conversation.Message.ContentItem.UserMessage -> item.text
        is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
        else -> null
    }
}.joinToString("\n\n").takeIf { it.isNotBlank() }
