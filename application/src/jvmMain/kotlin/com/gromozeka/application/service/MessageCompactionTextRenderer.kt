package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation

internal object MessageCompactionTextRenderer {
    fun render(messages: List<Conversation.Message>): String =
        messages.mapNotNull { message ->
            val content = message.content.mapNotNull(::renderContentItem)
                .filter(String::isNotBlank)
                .joinToString("\n")
            val instructions = message.instructions.filterNot { it is Conversation.Message.Instruction.RevealedSecretRuntimeContext }
                .joinToString("\n") { it.toXmlLine() }
            listOf(instructions, content).filter(String::isNotBlank).joinToString("\n").takeIf(String::isNotBlank)
                ?.let { "[${message.role.name.lowercase()} id=${message.id.value} at=${message.createdAt}]\n$it" }
        }.joinToString("\n\n").trim().also { require(it.isNotBlank()) { "Selected messages contain no readable content" } }

    fun attachments(messages: List<Conversation.Message>): List<Conversation.Message.ContentItem> = buildList {
        messages.flatMap { it.content }.forEach { item ->
            when (item) {
                is Conversation.Message.ContentItem.ImageItem, is Conversation.Message.ContentItem.DocumentItem -> add(item)
                is Conversation.Message.ContentItem.ToolResult -> item.result.forEach { data ->
                    when (data) {
                        is Conversation.Message.ContentItem.ToolResult.Data.Base64Data -> when {
                            data.mediaType.value.startsWith("image/") -> add(Conversation.Message.ContentItem.ImageItem(
                                Conversation.Message.ImageSource.Base64ImageSource(data.data, data.mediaType.value)))
                            data.mediaType.value == "application/pdf" -> add(Conversation.Message.ContentItem.DocumentItem(
                                Conversation.Message.DocumentSource.Base64DocumentSource(data.data, data.mediaType.value, data.fileName ?: "attachment.pdf")))
                        }
                        is Conversation.Message.ContentItem.ToolResult.Data.UrlData -> if (data.mediaType?.value?.startsWith("image/") == true)
                            add(Conversation.Message.ContentItem.ImageItem(Conversation.Message.ImageSource.UrlImageSource(data.url)))
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }
    }

    private fun renderContentItem(item: Conversation.Message.ContentItem): String? = when (item) {
        is Conversation.Message.ContentItem.UserMessage -> item.text
        is Conversation.Message.ContentItem.VisualInteraction -> item.modelText()
        is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
        is Conversation.Message.ContentItem.Thinking -> item.thinking.takeIf(String::isNotBlank)
        is Conversation.Message.ContentItem.System -> item.content
        is Conversation.Message.ContentItem.ToolCall -> "[tool_call:${item.call.name}] id=${item.id.value} ${item.call.input}"
        is Conversation.Message.ContentItem.ToolResult -> buildString {
            append("[tool_result:${item.toolName} error=${item.isError} call_id=${item.toolUseId.value}]")
            item.result.forEach { result ->
                append('\n')
                append(
                    when (result) {
                        is Conversation.Message.ContentItem.ToolResult.Data.Text -> result.content
                        is Conversation.Message.ContentItem.ToolResult.Data.Base64Data ->
                            "[binary:${result.fileName ?: result.mediaType.value} media_type=${result.mediaType.value}]"
                        is Conversation.Message.ContentItem.ToolResult.Data.UrlData -> "[url:${result.url}]"
                        is Conversation.Message.ContentItem.ToolResult.Data.ArtifactData ->
                            "[attachment:${result.artifact.fileName} id=${result.artifact.id.value} media_type=${result.artifact.mediaType}]"
                    }
                )
            }
        }
        is Conversation.Message.ContentItem.ImageItem -> when (val source = item.source) {
            is Conversation.Message.ImageSource.Base64ImageSource -> "[image:${source.mediaType}]"
            is Conversation.Message.ImageSource.UrlImageSource -> "[image:${source.url}]"
            is Conversation.Message.ImageSource.FileImageSource -> "[image:${source.fileId}]"
        }
        is Conversation.Message.ContentItem.DocumentItem -> when (val source = item.source) {
            is Conversation.Message.DocumentSource.Base64DocumentSource ->
                "[document:${source.fileName} media_type=${source.mediaType}]"
        }
        is Conversation.Message.ContentItem.ArtifactItem ->
            "[attachment:${item.artifact.fileName} id=${item.artifact.id.value} media_type=${item.artifact.mediaType}]"
        is Conversation.Message.ContentItem.ContextCompactionResult -> when (val payload = item.payload) {
            is Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary -> payload.text
            is Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState ->
                error("Opaque compaction must be recovered from its original messages before rendering")
        }
        is Conversation.Message.ContentItem.UnknownJson -> item.json.toString()
    }
}
