package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.Conversation

fun AiModelSpec.requireSupportsInputs(messages: List<Conversation.Message>) {
    val required = messages
        .flatMap(Conversation.Message::content)
        .flatMap(Conversation.Message.ContentItem::requiredInputCapabilities)
        .toSet()
    val missing = required - capabilities
    require(missing.isEmpty()) {
        "AI model $provider/$id does not support required input capabilities: " +
            missing.sortedBy(AiModelCapability::name).joinToString()
    }
}

private fun Conversation.Message.ContentItem.requiredInputCapabilities(): Set<AiModelCapability> =
    when (this) {
        is Conversation.Message.ContentItem.ImageItem -> setOf(AiModelCapability.IMAGE_INPUT)
        is Conversation.Message.ContentItem.DocumentItem -> setOf(AiModelCapability.DOCUMENT_INPUT)
        is Conversation.Message.ContentItem.ArtifactItem -> artifact.requiredInputCapabilities()
        is Conversation.Message.ContentItem.ToolResult -> result
            .flatMap(Conversation.Message.ContentItem.ToolResult.Data::requiredInputCapabilities)
            .toSet()
        else -> emptySet()
    }

private fun Conversation.Message.ContentItem.ToolResult.Data.requiredInputCapabilities(): Set<AiModelCapability> =
    when (this) {
        is Conversation.Message.ContentItem.ToolResult.Data.Base64Data ->
            if (mediaType.value.startsWith("image/")) {
                setOf(AiModelCapability.IMAGE_INPUT)
            } else {
                setOf(AiModelCapability.DOCUMENT_INPUT)
            }
        is Conversation.Message.ContentItem.ToolResult.Data.ArtifactData ->
            artifact.requiredInputCapabilities()
        is Conversation.Message.ContentItem.ToolResult.Data.UrlData ->
            if (mediaType?.value?.startsWith("image/") == true) {
                setOf(AiModelCapability.IMAGE_INPUT)
            } else {
                emptySet()
            }
        is Conversation.Message.ContentItem.ToolResult.Data.Text -> emptySet()
    }

private fun Artifact.Reference.requiredInputCapabilities(): Set<AiModelCapability> =
    when (kind) {
        Artifact.Kind.IMAGE -> setOf(AiModelCapability.IMAGE_INPUT)
        Artifact.Kind.DOCUMENT,
        Artifact.Kind.FILE -> setOf(AiModelCapability.DOCUMENT_INPUT)
    }
