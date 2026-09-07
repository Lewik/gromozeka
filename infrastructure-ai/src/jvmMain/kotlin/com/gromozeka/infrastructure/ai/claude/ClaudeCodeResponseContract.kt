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
        Return exactly one JSON object as your final text, without Markdown fences or commentary outside it.
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

    fun correction(errors: List<String>): String = buildString {
        appendLine("The previous response failed JSON validation. No Gromozeka actions from it were executed.")
        appendLine("Correct that response using the validation errors below. Return the complete corrected object.")
        appendLine("Validation errors: ${JsonArray(errors.map(::JsonPrimitive))}")
        appendLine()
        append(reminder())
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

internal const val CLAUDE_CODE_MAX_FORMAT_CORRECTIONS = 3

internal class ClaudeCodeResponseFormatException : IllegalStateException(
    "Claude Code returned invalid JSON or violated the response schema after $CLAUDE_CODE_MAX_FORMAT_CORRECTIONS correction attempts. No actions from the response were executed."
)
