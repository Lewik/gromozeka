package com.gromozeka.server

import com.gromozeka.domain.model.ai.AiCallException
import com.gromozeka.domain.model.ai.AiCallFailure
import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.tool.*
import com.gromozeka.domain.tool.AiToolContextField.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

@Service
internal class AiCallToolContributor(private val service: ObjectProvider<AiCallService>) : AiToolCallbackContributor {
    override val callbacks = listOf(object : AiToolCallback {
        override val definition = AiToolDefinition(
            name = "grz_ai_call",
            description = "Run one isolated, tool-free model call on explicit inputs and a prompt. Choose model_configuration_id from grz_ai_catalog_get. No parent history, Agent prompts, memory, tools, native web, conversation creation, compaction, model fallback or JSON repair generation. Inputs are ordered text, committed artifacts from this conversation, or files on exact workspace mounts in this project (UTF-8 text, supported images/PDF). File contents are read by Harness, not returned to the caller. At most 32 inputs and 12 MiB of prompt/input bytes; oversize or unsupported input fails, never truncates. response_schema requests validated JSON, otherwise plain text; no assistant/TTS envelope. result_mode=artifact stores only the answer and returns its reference, never the answer text. Refusal, incomplete and invalid output are errors with explicit status, available output and usage. Per-call max_output_tokens is unsupported by Claude Code and OpenAI Subscription adapters. Calls use existing model quotas/credentials; data goes to the selected provider. Copilot is unsupported. From Python, keep intermediate results in variables and print only the selected result; SDK calls are not automatically retried.",
            inputSchema = SCHEMA,
            annotations = AiToolAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = true),
        )
        override val metadata = AiToolMetadata(
            executionScope = AiToolExecutionScope.SERVER,
            contextRequirements = AiToolContextRequirements(setOf(USER, CONVERSATION)),
            visibleToMemoryPipeline = false, logInput = false,
        )
        override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking { callResult(toolInput, context).renderText() }
        override suspend fun callResult(toolInput: String, context: ToolExecutionContext?): AiToolCallResult = coroutineScope {
            val input = try { parse(toolInput) }
            catch (_: Exception) { return@coroutineScope failure("invalid_argument", "Invalid grz_ai_call arguments; see the tool schema") }
            val trusted = context ?: return@coroutineScope failure("unauthenticated", "An authenticated conversation context is required")
            try {
                val response = service.getObject().call(input, trusted)
                val result = buildJsonObject {
                    put("invocation_id", response.invocationId)
                    put("model_configuration_id", response.configurationId)
                    put("status", response.status)
                    response.output?.let { put("output", it) }
                    response.artifact?.let { put("artifact", serverToolJson.encodeToJsonElement(Artifact.Reference.serializer(), it)) }
                    put("usage", response.usage?.let { serverToolJson.encodeToJsonElement(AiUsage.serializer(), it) } ?: JsonNull)
                    response.errorCode?.let { put("error_code", it) }
                }
                payload(buildJsonObject { put("success", response.success); put("result", result) }, !response.success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AiCallException) {
                payload(buildJsonObject {
                    put("success", false)
                    put("error", buildJsonObject {
                        put("code", "ai_call_failed")
                        put("message", "Isolated model request failed; no fallback was used")
                        put("aiFailure", serverToolJson.encodeToJsonElement(AiCallFailure.serializer(), error.failure))
                    })
                }, true)
            } catch (_: UnsupportedOperationException) {
                failure("unsupported", "The selected adapter does not support this isolated call or requested option")
            } catch (_: Exception) {
                // File, schema and provider exceptions can include input text or credentials.
                // Never expose raw messages, causes or suppressed exceptions through MCP.
                failure("ai_call_failed", "Isolated AI call failed: check access, inputs, model capabilities and configuration. No fallback was used")
            }
        }
    })

    private fun payload(value: JsonObject, error: Boolean) = AiToolCallResult(
        content = listOf(AiToolResult.Text(value.toString())), structuredContent = value, isError = error)
    private fun failure(code: String, message: String) = payload(buildJsonObject {
        put("success", false); put("error", buildJsonObject { put("code", code); put("message", message) })
    }, true)

    private fun parse(text: String): AiCallRequest {
        require(text.encodeToByteArray().size <= 2 * AiCallService.MAX_INPUT_BYTES) { "Arguments too large" }
        val value = Json.parseToJsonElement(text).jsonObject
        require(value.keys.all { it in setOf("model_configuration_id", "prompt", "inputs", "response_schema", "result_mode", "max_output_tokens") })
        fun JsonObject.string(key: String): String {
            val item = getValue(key)
            require(item is JsonPrimitive && item.isString)
            return item.content
        }
        val model = value.string("model_configuration_id").also { require(it.isNotBlank()) }
        val prompt = value.string("prompt").also { require(it.isNotBlank() && it.length <= AiCallService.MAX_PROMPT_CHARACTERS) }
        val rawInputs = value["inputs"]?.let { it as JsonArray } ?: JsonArray(emptyList())
        require(rawInputs.size <= AiCallService.MAX_INPUTS)
        val inputs = rawInputs.map { raw ->
            val item = raw.jsonObject
            when (val type = item.string("type")) {
                "text" -> { require(item.keys == setOf("type", "text")); AiCallInput(type, text = item.string("text")) }
                "artifact" -> { require(item.keys == setOf("type", "artifact_id")); AiCallInput(type, artifactId = item.string("artifact_id").also { require(it.isNotBlank()) }) }
                "file" -> {
                    require(item.keys.all { it in setOf("type", "workspace_mount_id", "path", "media_type") })
                    AiCallInput(type, workspaceMountId = item.string("workspace_mount_id").also { require(it.isNotBlank()) },
                        path = item.string("path").also { require(it.isNotBlank()) },
                        mediaType = if ("media_type" in item) item.string("media_type").also { require(it.isNotBlank()) } else null)
                }
                else -> error("Unsupported input kind")
            }
        }
        val schema = value["response_schema"]?.let { it as JsonObject }
        val mode = if ("result_mode" in value) value.string("result_mode") else "inline"
        require(mode == "inline" || mode == "artifact")
        val maxOutput = value["max_output_tokens"]?.let {
            require(it is JsonPrimitive && !it.isString)
            requireNotNull(it.intOrNull).also { tokens -> require(tokens > 0) }
        }
        return AiCallRequest(model, prompt, inputs, schema, mode == "artifact", maxOutput)
    }

    companion object {
        private val SCHEMA = """{
          "type":"object","additionalProperties":false,
          "properties":{
            "model_configuration_id":{"type":"string","minLength":1,"description":"Exact model configuration ID from grz_ai_catalog_get; not a provider model name."},
            "prompt":{"type":"string","minLength":1,"maxLength":64000,"description":"Instruction for this call. No parent prompts are inherited."},
            "inputs":{"type":"array","maxItems":32,"items":{"oneOf":[
              {"type":"object","additionalProperties":false,"properties":{"type":{"const":"text"},"text":{"type":"string"}},"required":["type","text"]},
              {"type":"object","additionalProperties":false,"properties":{"type":{"const":"artifact"},"artifact_id":{"type":"string","minLength":1}},"required":["type","artifact_id"]},
              {"type":"object","additionalProperties":false,"properties":{"type":{"const":"file"},"workspace_mount_id":{"type":"string","minLength":1},"path":{"type":"string","minLength":1},"media_type":{"type":"string","minLength":1}},"required":["type","workspace_mount_id","path"]}
            ]}},
            "response_schema":{"type":"object","description":"Optional JSON Schema. Local references only. Invalid output is returned as an error without repair calls."},
            "result_mode":{"type":"string","enum":["inline","artifact"],"description":"inline returns the answer; artifact returns only a committed artifact reference."},
            "max_output_tokens":{"type":"integer","minimum":1,"description":"Optional per-call output cap; omitted uses existing model settings. Unsupported overrides fail before inference."}
          },"required":["model_configuration_id","prompt"]
        }"""
    }
}
