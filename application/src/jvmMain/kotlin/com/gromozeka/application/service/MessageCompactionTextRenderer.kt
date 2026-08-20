package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation

internal object MessageCompactionTextRenderer {
    fun render(messages: List<Conversation.Message>): String =
        messages.joinToString("\n\n") { message ->
            val content = message.content.mapNotNull(::renderContentItem)
                .filter(String::isNotBlank)
                .joinToString("\n")
            "[${message.role.name.lowercase()}]\n$content"
        }.trim().also { require(it.isNotBlank()) { "Selected messages contain no readable content" } }

    private fun renderContentItem(item: Conversation.Message.ContentItem): String? = when (item) {
        is Conversation.Message.ContentItem.UserMessage -> item.text
        is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
        is Conversation.Message.ContentItem.Thinking -> item.thinking.takeIf(String::isNotBlank)
        is Conversation.Message.ContentItem.System -> item.content
        is Conversation.Message.ContentItem.ToolCall -> "[tool_call:${item.call.name}] ${item.call.input}"
        is Conversation.Message.ContentItem.ToolResult -> buildString {
            append("[tool_result:${item.toolName} error=${item.isError}]")
            item.result.forEach { result ->
                append('\n')
                append(
                    when (result) {
                        is Conversation.Message.ContentItem.ToolResult.Data.Text -> result.content
                        is Conversation.Message.ContentItem.ToolResult.Data.Base64Data ->
                            "[binary:${result.fileName ?: result.mediaType.value} media_type=${result.mediaType.value}]"
                        is Conversation.Message.ContentItem.ToolResult.Data.UrlData -> "[url:${result.url}]"
                        is Conversation.Message.ContentItem.ToolResult.Data.ArtifactData ->
                            "[attachment:${result.artifact.fileName} media_type=${result.artifact.mediaType}]"
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
            "[attachment:${item.artifact.fileName} media_type=${item.artifact.mediaType}]"
        is Conversation.Message.ContentItem.ContextCompactionResult -> when (val payload = item.payload) {
            is Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary -> payload.text
            is Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState ->
                "[context_compaction:${item.providerScope?.provider ?: "unknown"}]"
        }
        is Conversation.Message.ContentItem.UnknownJson -> item.json.toString()
    }
}
