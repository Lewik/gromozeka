package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.requireSupportsInputs
import com.gromozeka.domain.service.ResolvedAiRuntime
import java.util.Base64 as JavaBase64

internal object AiRuntimeInputValidator {
    fun requireSupported(
        runtime: ResolvedAiRuntime,
        messages: List<Conversation.Message>,
    ) {
        runtime.modelSpec.requireSupportsInputs(messages)
        val mediaInputs = messages.flatMap { it.mediaInputs() }
        if (mediaInputs.isEmpty()) return

        when (runtime.connection.kind) {
            AiConnection.Kind.OPENAI_API,
            AiConnection.Kind.OPENAI_SUBSCRIPTION -> validateOpenAi(mediaInputs)
            AiConnection.Kind.OPENAI_COMPATIBLE -> validateOpenAiCompatible(mediaInputs)
            AiConnection.Kind.ANTHROPIC_API,
            AiConnection.Kind.CLAUDE_CODE -> validateAnthropic(mediaInputs, MAX_ANTHROPIC_IMAGE_BASE64_BYTES, true)
            AiConnection.Kind.ANTHROPIC_BEDROCK ->
                validateAnthropic(mediaInputs, MAX_BEDROCK_IMAGE_BASE64_BYTES, false)
            AiConnection.Kind.GEMINI_API,
            AiConnection.Kind.OLLAMA -> error(
                "Binary inputs are not implemented for connection kind ${runtime.connection.kind}"
            )
        }
    }

    private fun validateOpenAi(inputs: List<MediaInput>) {
        val images = inputs.filterIsInstance<MediaInput.Image>()
        require(images.size <= MAX_OPENAI_IMAGES) {
            "OpenAI accepts at most $MAX_OPENAI_IMAGES image inputs per request"
        }
        val imageBytes = images.sumOf { image ->
            when (val source = image.source) {
                is MediaSource.EmbeddedBase64 -> {
                    require(source.mediaType in OPENAI_IMAGE_MEDIA_TYPES) {
                        "OpenAI does not support image type ${source.mediaType}"
                    }
                    source.decodedSizeBytes.toLong()
                }
                is MediaSource.Url,
                is MediaSource.FileId -> 0L
            }
        }
        require(imageBytes <= MAX_OPENAI_IMAGE_PAYLOAD_BYTES) {
            "OpenAI image payload exceeds $MAX_OPENAI_IMAGE_PAYLOAD_BYTES bytes"
        }

        val documents = inputs.filterIsInstance<MediaInput.Document>()
        val documentBytes = documents.map { document ->
            val source = document.source
            require(source.mediaType.isOpenAiDocumentType()) {
                "OpenAI does not support document type ${source.mediaType}"
            }
            require(source.decodedSizeBytes < MAX_OPENAI_FILE_BYTES) {
                "OpenAI input file ${document.fileName} must be smaller than $MAX_OPENAI_FILE_BYTES bytes"
            }
            source.decodedSizeBytes.toLong()
        }
        require(documentBytes.sum() <= MAX_OPENAI_FILE_BYTES) {
            "OpenAI combined input files exceed $MAX_OPENAI_FILE_BYTES bytes"
        }
    }

    private fun validateOpenAiCompatible(inputs: List<MediaInput>) {
        inputs.forEach { input ->
            when (input) {
                is MediaInput.Image -> when (val source = input.source) {
                    is MediaSource.EmbeddedBase64 -> require(source.mediaType.startsWith("image/")) {
                        "OpenAI-compatible image input must have an image media type"
                    }
                    is MediaSource.Url -> Unit
                    is MediaSource.FileId -> error(
                        "OpenAI-compatible chat does not accept an image file id"
                    )
                }
                is MediaInput.Document -> Unit
            }
        }
    }

    private fun validateAnthropic(
        inputs: List<MediaInput>,
        maxImageBase64Bytes: Int,
        urlImagesAllowed: Boolean,
    ) {
        var encodedBinaryBytes = 0L
        inputs.forEach { input ->
            when (input) {
                is MediaInput.Image -> when (val source = input.source) {
                    is MediaSource.EmbeddedBase64 -> {
                        require(source.mediaType in ANTHROPIC_IMAGE_MEDIA_TYPES) {
                            "Anthropic does not support image type ${source.mediaType}"
                        }
                        require(source.encodedSizeBytes <= maxImageBase64Bytes) {
                            "Anthropic image exceeds the $maxImageBase64Bytes-byte base64 limit"
                        }
                        encodedBinaryBytes += source.encodedSizeBytes
                    }
                    is MediaSource.Url -> require(urlImagesAllowed) {
                        "Anthropic Bedrock accepts only base64 image sources"
                    }
                    is MediaSource.FileId -> error(
                        "Anthropic runtime does not accept a provider-independent image file id"
                    )
                }
                is MediaInput.Document -> {
                    require(input.source.mediaType.isAnthropicDocumentType()) {
                        "Anthropic does not support document type ${input.source.mediaType}"
                    }
                    encodedBinaryBytes += input.source.encodedSizeBytes
                }
            }
        }
        require(encodedBinaryBytes <= MAX_ANTHROPIC_REQUEST_BYTES) {
            "Anthropic binary payload exceeds the $MAX_ANTHROPIC_REQUEST_BYTES-byte request limit"
        }
    }

    private fun Conversation.Message.mediaInputs(): List<MediaInput> = content.flatMap { item ->
        when (item) {
            is Conversation.Message.ContentItem.ImageItem -> listOf(MediaInput.Image(item.source.toMediaSource()))
            is Conversation.Message.ContentItem.DocumentItem -> when (val source = item.source) {
                is Conversation.Message.DocumentSource.Base64DocumentSource -> listOf(
                    MediaInput.Document(
                        source = MediaSource.EmbeddedBase64(source.mediaType.normalizedMediaType(), source.data),
                        fileName = source.fileName,
                    )
                )
            }
            is Conversation.Message.ContentItem.ArtifactItem -> error(
                "AI runtime received an unmaterialized artifact: ${item.artifact.id.value}"
            )
            is Conversation.Message.ContentItem.ToolResult -> item.result.mapNotNull { data ->
                when (data) {
                    is Conversation.Message.ContentItem.ToolResult.Data.Base64Data -> {
                        val source = MediaSource.EmbeddedBase64(data.mediaType.value.normalizedMediaType(), data.data)
                        if (source.mediaType.startsWith("image/")) {
                            MediaInput.Image(source)
                        } else {
                            MediaInput.Document(source, data.fileName ?: "tool-output.bin")
                        }
                    }
                    is Conversation.Message.ContentItem.ToolResult.Data.UrlData ->
                        if (data.mediaType?.value?.startsWith("image/") == true) {
                            MediaInput.Image(MediaSource.Url(data.url))
                        } else {
                            null
                        }
                    is Conversation.Message.ContentItem.ToolResult.Data.ArtifactData -> error(
                        "AI runtime received an unmaterialized tool artifact: ${data.artifact.id.value}"
                    )
                    is Conversation.Message.ContentItem.ToolResult.Data.Text -> null
                }
            }
            else -> emptyList()
        }
    }

    private fun Conversation.Message.ImageSource.toMediaSource(): MediaSource = when (this) {
        is Conversation.Message.ImageSource.Base64ImageSource ->
            MediaSource.EmbeddedBase64(mediaType.normalizedMediaType(), data)
        is Conversation.Message.ImageSource.UrlImageSource -> MediaSource.Url(url)
        is Conversation.Message.ImageSource.FileImageSource -> MediaSource.FileId(fileId)
    }

    private fun String.normalizedMediaType(): String = substringBefore(';').trim().lowercase()

    private fun String.isOpenAiDocumentType(): Boolean =
        startsWith("text/") || this in OPENAI_DOCUMENT_MEDIA_TYPES

    private fun String.isAnthropicDocumentType(): Boolean =
        this == "application/pdf" || startsWith("text/") || this in ANTHROPIC_TEXT_DOCUMENT_MEDIA_TYPES

    private sealed interface MediaInput {
        data class Image(val source: MediaSource) : MediaInput
        data class Document(val source: MediaSource.EmbeddedBase64, val fileName: String) : MediaInput
    }

    private sealed interface MediaSource {
        data class EmbeddedBase64(val mediaType: String, val data: String) : MediaSource {
            val encodedSizeBytes: Int = data.length
            val decodedSizeBytes: Int = try {
                JavaBase64.getDecoder().decode(data).size
            } catch (exception: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid base64 input for media type $mediaType", exception)
            }
        }

        data class Url(val value: String) : MediaSource
        data class FileId(val value: String) : MediaSource
    }

    private const val MEBIBYTE = 1024 * 1024
    private const val MAX_OPENAI_IMAGES = 1_500
    private const val MAX_OPENAI_IMAGE_PAYLOAD_BYTES = 512L * MEBIBYTE
    private const val MAX_OPENAI_FILE_BYTES = 50L * MEBIBYTE
    private const val MAX_ANTHROPIC_IMAGE_BASE64_BYTES = 10 * MEBIBYTE
    private const val MAX_BEDROCK_IMAGE_BASE64_BYTES = 5 * MEBIBYTE
    private const val MAX_ANTHROPIC_REQUEST_BYTES = 32L * MEBIBYTE

    private val OPENAI_IMAGE_MEDIA_TYPES = setOf(
        "image/png",
        "image/jpeg",
        "image/webp",
        "image/gif",
    )
    private val ANTHROPIC_IMAGE_MEDIA_TYPES = OPENAI_IMAGE_MEDIA_TYPES
    private val ANTHROPIC_TEXT_DOCUMENT_MEDIA_TYPES = setOf(
        "application/json",
        "application/xml",
        "application/x-yaml",
        "application/yaml",
    )
    private val OPENAI_DOCUMENT_MEDIA_TYPES = ANTHROPIC_TEXT_DOCUMENT_MEDIA_TYPES + setOf(
        "application/pdf",
        "application/csv",
        "application/javascript",
        "application/typescript",
        "application/msword",
        "application/rtf",
        "application/vnd.ms-excel",
        "application/vnd.ms-powerpoint",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.apple.iwork",
        "application/vnd.apple.keynote",
        "application/vnd.apple.pages",
        "application/vnd.google-apps.document",
        "application/vnd.google-apps.presentation",
        "application/vnd.google-apps.spreadsheet",
        "application/x-iif",
        "application/x-ndjson",
        "application/x-sql",
        "application/x-toml",
        "application/toml",
        "message/rfc822",
    )
}
