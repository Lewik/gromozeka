package com.gromozeka.infrastructure.ai.claude

import com.gromozeka.domain.tool.AiToolCallback
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import io.github.optimumcode.json.schema.JsonSchema
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class ClaudeCodeResponseContract(
    val schema: JsonElement,
    tools: List<AiToolCallback>,
    private val externalActions: Boolean,
) {
    private val validator = JsonSchema.fromJsonElement(schema)
    private val actionValidators = if (externalActions) tools.associate { tool ->
        tool.definition.name to JsonSchema.fromDefinition(tool.definition.inputSchema)
    } else emptyMap()

    fun instructions(): String =
        """
        Your entire assistant text output must be exactly one JSON object, without Markdown fences or commentary outside it.
        Do not emit earlier text blocks containing simulated action calls, results, or completion reports.
        Your output will be parsed as JSON directly without preprocessing.
        Follow this JSON Schema:
        <json_schema>
        $schema
        </json_schema>
        """.trimIndent()

    fun reminder(): String = buildString {
        appendLine("<system-reminder>")
        appendLine("Return exactly one JSON object matching the JSON Schema in the system prompt.")
        appendLine("Do not add Markdown fences or text outside that JSON object.")
        if (externalActions) {
            appendLine()
            appendLine("Gromozeka actions are not Claude Code native tools. Never invoke them through native tool use.")
            appendLine("When actions are needed, use response.kind=\"tool_calls\" and put all currently independent action requests in response.content as kind=\"tool_call\" entries.")
            appendLine("You may include user-facing kind=\"message\" entries in that ordered array. They are remarks, not tool results.")
            appendLine("Gromozeka will execute those actions and provide their results.")
            appendLine("Otherwise, use response.kind=\"final_answer\" and put the answer in response.final_answer.")
        }
        append("</system-reminder>")
    }

    fun validationErrors(text: String): List<String> {
        val value = try {
            syntaxParser.readTree(text)
            Json.parseToJsonElement(text)
        } catch (_: JsonProcessingException) {
            return listOf(INVALID_JSON_MESSAGE)
        } catch (_: SerializationException) {
            return listOf(INVALID_JSON_MESSAGE)
        }
        if (value !is JsonObject) return listOf("The response must be a JSON object.")
        val errors = mutableListOf<String>()
        if (!validate(validator, value, "", errors)) return errors
        if (externalActions) {
            val branch = value.getValue("response").jsonObject
            if (branch.getValue("kind").jsonPrimitive.content == "tool_calls") {
                val content = branch.getValue("content").jsonArray
                if (content.none { it.jsonObject.getValue("kind").jsonPrimitive.content == "tool_call" }) {
                    errors += "/response/content: at least one tool_call is required"
                }
                content.forEachIndexed { index, element ->
                    val call = element.jsonObject
                    if (call.getValue("kind").jsonPrimitive.content != "tool_call") return@forEachIndexed
                    val action = call.getValue("action_name").jsonPrimitive.content
                    validate(actionValidators.getValue(action), call.getValue("arguments"), "/response/content/$index/arguments", errors)
                }
            }
        }
        return errors
    }

    private fun validate(validator: JsonSchema, value: JsonElement, prefix: String, errors: MutableList<String>): Boolean =
        validator.validate(value) { error ->
            if (errors.size < 20) {
                errors += "$prefix${error.objectPath}: ${error.message} (schema ${error.schemaPath})".take(1_000)
            }
        }

    private companion object {
        const val INVALID_JSON_MESSAGE = "The response is not valid JSON. Return one complete JSON object without any surrounding text."
        val syntaxParser = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()
    }
}

/** All completed assistant blocks, not the CLI's last-text projection, are the response. */
internal fun ClaudeCodeCliResponse.completeAssistantText(): String {
    val textBlocks = mutableListOf<String>()
    val seen = mutableMapOf<String, JsonObject>()
    fun reject(reason: String): Nothing = throw ClaudeCodeResponseFormatException(reason = reason)
    for (event in replayEvents) {
        val type = (event["type"] as? JsonPrimitive)?.contentOrNull
        if (type !in setOf("assistant", "user")) continue
        if (event["parent_tool_use_id"]?.let { it !is JsonNull } == true) reject("unexpected_subagent_output")
        val message = event["message"] as? JsonObject ?: reject("missing_message_payload")
        val content = message["content"]
        if (type == "user") {
            if (content is JsonArray && content.any {
                (it as? JsonObject)?.get("type") == JsonPrimitive("tool_result")
            }) reject("unexpected_native_tool_result")
            continue
        }
        // SDK content-block events share message.id, but have distinct frame UUIDs.
        // Only an exact duplicate frame may be ignored; never deduplicate by message.id or text.
        val id = (event["uuid"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: reject("missing_assistant_frame_id")
        val previous = seen.putIfAbsent(id, event)
        if (previous != null) {
            if (previous != event) reject("conflicting_assistant_frame")
            continue
        }
        when (content) {
            is JsonPrimitive -> if (content.isString) textBlocks += content.content else reject("invalid_assistant_content")
            is JsonArray -> content.forEach { element ->
                val block = element as? JsonObject ?: reject("invalid_assistant_block")
                when ((block["type"] as? JsonPrimitive)?.contentOrNull) {
                    "text" -> {
                        val text = block["text"] as? JsonPrimitive
                        if (text == null || !text.isString) reject("invalid_text_block")
                        textBlocks += text.content
                    }
                    "thinking", "redacted_thinking" -> Unit // Never rewrite or scan signed reasoning.
                    else -> reject("unexpected_assistant_block") // Native tools are disabled in the external-action runtime.
                }
            }
            else -> reject("missing_assistant_content")
        }
    }
    if (textBlocks.isEmpty()) reject("missing_assistant_text")
    val complete = textBlocks.joinToString("")
    // A terminal result may project the last completed block, not the entire response.
    if (result.trim() != complete.trim() && result.trim() != textBlocks.last().trim()) {
        reject("terminal_text_mismatch")
    }
    return complete
}

internal const val CLAUDE_CODE_CLEAN_RETRY_REMINDER = """<system-reminder>
A previous candidate response was rejected. No external Gromozeka actions from that candidate were dispatched.
This is the single automatic retry in a fresh native session. Use only the supplied conversation history;
do not assume any additional action was executed or invent its results. Follow the response contract for your entire text output.
</system-reminder>"""

internal class ClaudeCodeResponseFormatException(
    val reason: String = "invalid_full_response",
    val diagnosticId: String? = null,
    val responseFingerprint: String? = null,
    val usage: JsonObject? = null,
    val cleanRetryFailed: Boolean = false,
) : IllegalStateException(
    "Claude Code response rejected ($reason)" +
        (if (cleanRetryFailed) " after the single automatic clean-session retry" else "") +
        ". No external Gromozeka actions from this response were dispatched. The rejected native session will not be reused." +
        (diagnosticId?.let { " Diagnostic: $it." } ?: "") +
        (responseFingerprint?.let { " Response SHA-256: $it." } ?: "")
)
