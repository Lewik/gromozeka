package com.gromozeka.application.service.memory

import com.gromozeka.application.service.MemoryToolApplicationService
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component

@Component
class MemoryRememberToolCallback(
    private val memoryToolApplicationService: MemoryToolApplicationService,
) : AiToolCallback {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class Input(
        val target: String = "previous_user_message",
        val target_message_id: String? = null,
        val text: String? = null,
        val file_path: String? = null,
        val raw_url: String? = null,
        val document_type: String? = null,
        val title: String? = null,
        val source_ref: String? = null,
        val user_consent_confirmed: Boolean = false,
        val force_write: Boolean = false,
        val mode: String? = null,
        val namespace: String? = null,
    )

    override val definition: AiToolDefinition = AiToolDefinition(
        name = MEMORY_REMEMBER_TOOL_NAME,
        description = "Persist memory-worthy information into typed memory. Use previous_user_message/message_id for normal conversation memory writes. Provided content modes can run without conversation context and are only allowed when the user explicitly asks or consents to remember that exact arbitrary text/document. Optional namespace is a readable memory boundary such as global, user:lewik, work:hebrew, or project:<project-id>; omit it to use the configured default or current project namespace. For documents, pass exactly one of text, file_path, or raw_url plus document_type='markdown'; document ingestion returns a queued run_id and continues in the background. raw_url must point to raw text/markdown, not a normal HTML web page. Do not use provided content modes for assistant-generated summaries, guesses, rewritten content, or hidden compression unless the user approved that exact text.",
        inputSchema = """
            {
              "type": "object",
              "properties": {
                "target": {
                  "type": "string",
                  "description": "Memory write target. Supports 'previous_user_message', 'message_id', 'provided_text', 'file_path', 'raw_url', and 'provided_document'."
                },
                "target_message_id": {
                  "type": "string",
                  "description": "Optional explicit message id in the current thread to use as the memory write target."
                },
                "text": {
                  "type": "string",
                  "description": "Exact arbitrary text to persist when target is 'provided_text' or document content when document_type is set. This mode is allowed only with explicit user consent."
                },
                "file_path": {
                  "type": "string",
                  "description": "Absolute or working-directory-relative path to a local raw text/markdown file to remember. Use only with explicit user consent."
                },
                "raw_url": {
                  "type": "string",
                  "description": "HTTP(S) URL that returns raw text/markdown, not an HTML web page. Use only with explicit user consent."
                },
                "document_type": {
                  "type": "string",
                  "description": "Optional document type for provided content. Currently supports 'markdown'. Required for text-as-document; inferred as markdown for file_path/raw_url."
                },
                "title": {
                  "type": "string",
                  "description": "Optional human-readable document title."
                },
                "source_ref": {
                  "type": "string",
                  "description": "Optional source reference such as a file path, raw URL, Confluence page label, or import id."
                },
                "user_consent_confirmed": {
                  "type": "boolean",
                  "description": "Must be true for provided content targets. Set it only when the user explicitly asked or agreed to remember the provided text/document."
                },
                "force_write": {
                  "type": "boolean",
                  "description": "Force ingestion for this exact target when the user explicitly says to remember/store/import it even if the router would normally choose no-op. Use sparingly; does not bypass user_consent_confirmed for provided content."
                },
                "mode": {
                  "type": "string",
                  "description": "Optional caller mode label for debugging or analytics."
                },
                "namespace": {
                  "type": "string",
                  "description": "Optional readable memory namespace to write into. Examples: global, user:lewik, work:hebrew, project:<project-id>. Omit to use the configured default or current project namespace."
                }
              }
            }
        """.trimIndent()
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        val input = parseInput(toolInput)
        val providedText = input.text?.trim().orEmpty()
        val providedFilePath = input.file_path?.trim().orEmpty()
        val providedRawUrl = input.raw_url?.trim().orEmpty()
        val hasProvidedContent = providedText.isNotBlank() || providedFilePath.isNotBlank() || providedRawUrl.isNotBlank()
        if (input.target in providedContentTargets || hasProvidedContent) {
            if (!hasProvidedContent) {
                return@runBlocking MemoryToolResultRenderer.failureJsonString(
                    "Provided content target requires exactly one of text, file_path, or raw_url."
                )
            }
            if (!input.user_consent_confirmed) {
                return@runBlocking MemoryToolResultRenderer.failureJsonString(
                    "Provided content targets require explicit user consent and user_consent_confirmed=true."
                )
            }
            return@runBlocking memoryToolApplicationService.rememberProvidedText(
                conversationIdValue = context?.getString("conversationId"),
                text = providedText.takeIf { it.isNotBlank() },
                filePath = providedFilePath.takeIf { it.isNotBlank() },
                rawUrl = providedRawUrl.takeIf { it.isNotBlank() },
                documentType = input.document_type,
                title = input.title,
                sourceRef = input.source_ref,
                forceWrite = input.force_write,
                mode = input.mode,
                namespaceValue = input.namespace,
            )
        }

        val conversationId = context?.getString("conversationId")
            ?: return@runBlocking MemoryToolResultRenderer.failureJsonString(
                "conversationId not found in ToolExecutionContext. target='${input.target}' can only run inside a conversation turn. Use target='provided_text' with explicit consent for standalone text."
            )

        if (input.target !in setOf("previous_user_message", "message_id")) {
            return@runBlocking MemoryToolResultRenderer.failureJsonString("Unsupported memory_remember target: ${input.target}")
        }
        if (input.target == "message_id" && input.target_message_id.isNullOrBlank()) {
            return@runBlocking MemoryToolResultRenderer.failureJsonString("target='message_id' requires target_message_id.")
        }

        memoryToolApplicationService.remember(
            conversationIdValue = conversationId,
            targetMessageId = input.target_message_id,
            forceWrite = input.force_write,
            namespaceValue = input.namespace,
        )
    }

    private fun parseInput(toolInput: String): Input {
        if (toolInput.isBlank() || toolInput == "{}") {
            return Input()
        }
        return json.decodeFromString(toolInput)
    }

    private companion object {
        val providedContentTargets = setOf("provided_text", "provided_document", "file_path", "raw_url")
    }
}
