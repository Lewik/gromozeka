package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message.ContentItem.ToolResult

object MessageCompactionTextRenderer {
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

    fun characterCount(message: Conversation.Message): Long =
        message.content.sumOf {
            when (it) {
                is Conversation.Message.ContentItem.ContextCompactionResult -> 0L
                is ToolResult -> resultHeader(it).length + it.result.sumOf { data -> 1L + resultText(data).length }
                else -> renderContentItem(it)?.length?.toLong() ?: 0L
            }
        } +
            message.instructions.filterNot { it is Conversation.Message.Instruction.RevealedSecretRuntimeContext }
                .sumOf { it.toXmlLine().length.toLong() }

    fun attachments(messages: List<Conversation.Message>): List<Conversation.Message.ContentItem> = buildList {
        messages.flatMap { it.content }.forEach { item ->
            when (item) {
                is Conversation.Message.ContentItem.ImageItem, is Conversation.Message.ContentItem.DocumentItem -> add(item)
                is Conversation.Message.ContentItem.ToolResult -> addAll(outputAttachments(item.result))
                else -> Unit
            }
        }
    }

    private fun outputAttachments(outputs: List<Conversation.Message.ContentItem.ToolResult.Data>): List<Conversation.Message.ContentItem> = buildList {
        outputs.forEach { data ->
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
    }

    private fun renderContentItem(item: Conversation.Message.ContentItem): String? = when (item) {
        is Conversation.Message.ContentItem.UserMessage -> item.text
        is Conversation.Message.ContentItem.VisualInteraction -> item.modelText()
        is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
        is Conversation.Message.ContentItem.Thinking -> item.thinking.takeIf(String::isNotBlank)
        is Conversation.Message.ContentItem.System -> item.content
        is Conversation.Message.ContentItem.ToolCall -> "[tool_call:${item.call.name}] id=${item.id.value} ${item.call.input}"
        is ToolResult -> buildString {
            append(resultHeader(item))
            item.result.forEach { append('\n').append(resultText(it)) }
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

    private fun resultHeader(item: ToolResult) = "[tool_result:${item.toolName} error=${item.isError} call_id=${item.toolUseId.value}]"

    private fun resultText(data: ToolResult.Data): String = when (data) {
        is ToolResult.Data.Text -> data.content
        is ToolResult.Data.Base64Data -> "[binary:${data.fileName ?: data.mediaType.value} media_type=${data.mediaType.value}]"
        is ToolResult.Data.UrlData -> "[url:${data.url}]"
        is ToolResult.Data.ArtifactData -> "[attachment:${data.artifact.fileName} id=${data.artifact.id.value} media_type=${data.artifact.mediaType}]"
    }
}
