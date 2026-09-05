package com.gromozeka.server

import com.gromozeka.application.service.ContextStateApplicationService
import com.gromozeka.domain.model.ContextEvent
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerPermission
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolCallbackContributor
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolLoadingPolicy
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredUserId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service

@Service
internal class ContextStateToolContributor(
    private val contextStateService: ContextStateApplicationService,
    private val userDirectoryService: UserDirectoryService,
    private val workerAccessService: WorkerAccessService,
) : AiToolCallbackContributor {
    override val callbacks: List<AiToolCallback> = listOf(
        callback(
            name = "declare_user_state",
            description = "Persist a current state fact explicitly stated by the authenticated user. Never use this for an inference or an unstated assumption.",
            schema = DECLARE_USER_STATE_SCHEMA,
        ) { input, userId ->
            contextStateService.declareState(
                userId = userId,
                subject = ContextEvent.Subject.UserState(userId),
                stateKey = input.requiredString("state_key"),
                value = input["value"] ?: error("value is required"),
            )
            """{"stored":true}"""
        },
        callback(
            name = "get_user_state",
            description = "Read the current active client, user declarations, mobile device states, and detected conflicts.",
            schema = EMPTY_OBJECT_SCHEMA,
        ) { _, userId ->
            contextStateJson.encodeToString(contextStateService.getUserState(userId))
        },
        callback(
            name = "get_device_state",
            description = "Read the current state reported by one accessible mobile Worker device.",
            schema = WORKER_ID_SCHEMA,
        ) { input, userId ->
            val worker = requireMobileWorker(userId, input.requiredString("worker_id"))
            contextStateJson.encodeToString(
                contextStateService.getDeviceState(requireNotNull(worker.subjectUserId), worker.id)
            )
        },
        callback(
            name = "query_state_history",
            description = "Read immutable user or mobile device state events in reverse chronological order.",
            schema = HISTORY_SCHEMA,
        ) { input, userId ->
            val worker = input.optionalString("worker_id")?.let { requireMobileWorker(userId, it) }
            val limit = input["limit"]?.jsonPrimitive?.intOrNull ?: 100
            val from = input.optionalString("from")?.let(Instant::parse)
            val to = input.optionalString("to")?.let(Instant::parse)
            contextStateJson.encodeToString(
                contextStateService.history(
                    userId = worker?.subjectUserId ?: userId,
                    workerId = worker?.id,
                    from = from,
                    to = to,
                    limit = limit,
                )
            )
        },
    )

    private fun callback(
        name: String,
        description: String,
        schema: String,
        execute: suspend (JsonObject, User.Id) -> String,
    ): AiToolCallback = object : AiToolCallback {
        override val definition = AiToolDefinition(name, description, schema)
        override val metadata = AiToolMetadata(
            executionScope = AiToolExecutionScope.SERVER,
            loadingPolicy = AiToolLoadingPolicy.PRELOAD_WHEN_AVAILABLE,
            visibleToMemoryPipeline = false,
        )

        override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
            try {
                val input = toolInput.takeIf(String::isNotBlank)
                    ?.let(contextStateJson::parseToJsonElement) as? JsonObject
                    ?: buildJsonObject {}
                execute(input, context.requiredUserId())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                contextStateJson.encodeToString(
                    buildJsonObject { put("error", error.message ?: "State tool failed") }
                )
            }
        }
    }

    private suspend fun requireMobileWorker(actorUserId: User.Id, workerId: String): WorkerResource {
        val actor = userDirectoryService.findActiveById(actorUserId)
            ?: error("State tools require an active authenticated user")
        return workerAccessService.requirePermission(
            actor = actor,
            workerId = ConversationRuntimeWorkerId(workerId),
            permission = WorkerPermission.USE,
        ).also {
            require(it.subjectUserId != null) { "Worker is not bound to a user for context reporting" }
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        optionalString(name) ?: error("$name is required")

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotEmpty)

    private companion object {
        const val EMPTY_OBJECT_SCHEMA =
            """{"type":"object","properties":{},"additionalProperties":false}"""
        const val DECLARE_USER_STATE_SCHEMA =
            """{"type":"object","properties":{"state_key":{"type":"string","pattern":"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"},"value":{}},"required":["state_key","value"],"additionalProperties":false}"""
        const val WORKER_ID_SCHEMA =
            """{"type":"object","properties":{"worker_id":{"type":"string"}},"required":["worker_id"],"additionalProperties":false}"""
        const val HISTORY_SCHEMA =
            """{"type":"object","properties":{"worker_id":{"type":"string"},"from":{"type":"string"},"to":{"type":"string"},"limit":{"type":"integer","minimum":1,"maximum":1000}},"additionalProperties":false}"""
    }
}

private val contextStateJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}
