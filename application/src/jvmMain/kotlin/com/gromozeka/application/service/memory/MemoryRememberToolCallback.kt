package com.gromozeka.application.service.memory

import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component

@Component
class MemoryRememberToolCallback(
    private val memoryOperations: MemoryAsyncOperationApplicationService,
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
        val force_write: Boolean? = null,
        val confirmed_preflight_run_id: String? = null,
        val mode: String? = null,
    )

    override val definition: AiToolDefinition = AiToolDefinition(
        name = MEMORY_REMEMBER_TOOL_NAME,
        description = "Queue persistence of memory-worthy information into typed memory and return a run_id immediately. Use memory_run_status with that run_id to retrieve completion and the final result. Use previous_user_message/message_id for normal conversation memory writes. Provided content modes can run without conversation context and are only allowed when the user explicitly asks or consents to remember that exact arbitrary text/document. Memory is written to the global namespace. For documents, pass exactly one of text, file_path, or raw_url plus document_type='markdown'. raw_url must point to raw text/markdown, not a normal HTML web page. Do not use provided content modes for assistant-generated summaries, guesses, rewritten content, or hidden compression unless the user approved that exact text.",
        inputSchema = """
            {
              "type": "object",
              "additionalProperties": false,
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
                  "description": "Optional explicit override. true forces ingestion after preflight; false disables configured document force; omit to use the configured document-ingest default. Set true only when the user explicitly requests forced storage. Does not bypass technical validation or user_consent_confirmed."
                },
                "confirmed_preflight_run_id": {
                  "type": "string",
                  "description": "Run id whose run_status is needs_input and whose proposed structure the user explicitly approved. Never set this without asking the user and receiving explicit confirmation."
                },
                "mode": {
                  "type": "string",
                  "description": "Optional caller mode label for debugging or analytics."
                }
              }
            }
        """.trimIndent()
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        val input = parseInput(toolInput)
        val writeSurface = MemoryWriteSurface.fromContextValue(context?.getString(MEMORY_WRITE_SURFACE_CONTEXT_KEY))
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
            return@runBlocking memoryOperations.rememberProvidedContent(
                conversationIdValue = context?.getString("conversationId"),
                text = providedText.takeIf { it.isNotBlank() },
                filePath = providedFilePath.takeIf { it.isNotBlank() },
                rawUrl = providedRawUrl.takeIf { it.isNotBlank() },
                documentType = input.document_type ?: "markdown".takeIf { input.target == "provided_document" },
                title = input.title,
                sourceRef = input.source_ref,
                forceWrite = input.force_write,
                confirmedPreflightRunId = input.confirmed_preflight_run_id,
                mode = input.mode,
                writeSurface = writeSurface,
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

        memoryOperations.rememberMessage(
            conversationIdValue = conversationId,
            targetMessageId = input.target_message_id,
            forceWrite = input.force_write,
            confirmedPreflightRunId = input.confirmed_preflight_run_id,
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
