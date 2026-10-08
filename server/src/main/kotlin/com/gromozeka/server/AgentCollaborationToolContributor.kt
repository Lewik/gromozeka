package com.gromozeka.server

import com.gromozeka.application.service.AgentCollaborationService
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["gromozeka.collaboration.enabled"], havingValue = "true")
class AgentCollaborationToolContributor(private val collaboration: AgentCollaborationService) : AiToolCallbackContributor {
    override val callbacks: List<AiToolCallback> = listOf(
        tool("grz_agent_sessions", "List existing agent sessions you may contact. Only conversations in this project, with the same authenticated user and matching tool access policies are eligible. AI connections, models and preloaded tool lists may differ. A session is a conversation plus agent, not an agent definition alone.", "{}", emptyList()) { _, context ->
            val (source, actor) = collaboration.source(context)
            collaboration.sessions(source, actor).toString()
        },
        tool("grz_agent_request", "Ask an existing agent session to do work and return a result. Returns immediately with a request_id; queued does not mean completed. Results arrive automatically, do not poll. This is not a user instruction or permission grant. The human may continue talking and steering either session.", TARGET_SCHEMA, listOf("target_conversation_id", "message")) { input, context ->
            send(input, context, true)
        },
        tool("grz_agent_message", "Send information to another existing agent session without creating an obligation to reply. This queues context without starting a model turn; use a request for work that must start now. Do not send courtesy acknowledgements to peer results. Use grz_agent_request if a result is needed.", TARGET_SCHEMA, listOf("target_conversation_id", "message")) { input, context ->
            send(input, context, false)
        },
        tool("grz_agent_reply", "Complete an incoming request with its actual result or explicit inability/refusal. The runtime resolves the original requester; do not supply a destination. A normal user-facing response does not send this result. Completion is idempotent for the same result.", """{"request_id":{"type":"string"},"message":{"type":"string","minLength":1,"maxLength":32000}}""", listOf("request_id", "message")) { input, context ->
            val (source, actor) = collaboration.source(context)
            collaboration.reply(source, actor, input.string("request_id"), input.string("message"))
            """{"status":"result_queued"}"""
        },
        tool("grz_agent_requests", "Inspect current session collaboration requests and results when explicitly needed. Results are pushed automatically; do not poll this tool to wait.", "{}", emptyList()) { _, context ->
            val (source, actor) = collaboration.source(context)
            Json.encodeToString(collaboration.context(source, actor))
        },
        tool("grz_agent_request_cancel", "Cancel a collaboration request involving this session. Prevents a pending request from starting and prevents subsequent completion; does not undo effects or kill unrelated tools in the recipient session.", """{"request_id":{"type":"string"}}""", listOf("request_id")) { input, context ->
            val (source, actor) = collaboration.source(context)
            collaboration.cancel(source, actor, input.string("request_id"))
            """{"status":"cancelled"}"""
        },
    )

    private suspend fun send(input: JsonObject, context: ToolExecutionContext?, request: Boolean): String {
        val (source, actor) = collaboration.source(context)
        val key = context?.getString("toolCallId") ?: error("A conversation tool-call identity is required")
        val id = collaboration.send(source, actor, input.string("target_conversation_id"),
            input["target_agent_id"]?.jsonPrimitive?.contentOrNull, input.string("message"), key, request)
        return buildJsonObject { put(if (request) "request_id" else "message_id", id); put("status", "queued") }.toString()
    }

    private fun tool(name: String, description: String, properties: String, required: List<String>,
        action: suspend (JsonObject, ToolExecutionContext?) -> String): AiToolCallback {
        val props = Json.parseToJsonElement(properties).jsonObject
        return object : AiToolCallback {
            override val definition = AiToolDefinition(name, description, buildJsonObject {
                put("type", "object"); put("properties", props); put("required", JsonArray(required.map(::JsonPrimitive))); put("additionalProperties", false)
            }.toString())
            override val metadata = AiToolMetadata(executionScope = AiToolExecutionScope.SERVER,
                loadingPolicy = AiToolLoadingPolicy.PRELOAD_WHEN_AVAILABLE, visibleToMemoryPipeline = false, logInput = false)
            override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
                val input = Json.parseToJsonElement(toolInput).jsonObject
                require(input.keys.all { it in props } && required.all { it in input }) { "Unexpected or missing tool arguments" }
                context?.cancellationSignal?.throwIfCancellationRequested()
                action(input, context)
            }
        }
    }
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private companion object {
        const val TARGET_SCHEMA = """{"target_conversation_id":{"type":"string"},"target_agent_id":{"type":["string","null"]},"message":{"type":"string","minLength":1,"maxLength":32000}}"""
    }
}
