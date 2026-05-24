package com.gromozeka.server

import com.gromozeka.application.service.MemoryToolApplicationService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val memoryHttpJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

fun Routing.gromozekaMemoryHttp(memoryToolApplicationService: MemoryToolApplicationService) {
    get("/memory/status") {
        val runId = call.request.queryParameters["run_id"]?.trim()?.takeIf { it.isNotEmpty() }
        val includeChildren = call.request.queryParameters["include_children"]?.toBooleanStrictOrNull() ?: true
        val maxDepth = call.request.queryParameters["max_depth"]?.toIntOrNull() ?: 4

        val response = runCatching {
            buildJsonObject {
                put("success", true)
                put("queue", memoryToolApplicationService.memoryQueueStatus().parseMemoryToolJson())
                put("namespaces", memoryToolApplicationService.listNamespaces().parseMemoryToolJson())
                if (runId != null) {
                    put(
                        "run",
                        memoryToolApplicationService.memoryRunStatus(
                            runIdValue = runId,
                            includeChildren = includeChildren,
                            maxDepth = maxDepth,
                        ).parseMemoryToolJson()
                    )
                }
            }
        }

        response.fold(
            onSuccess = { payload ->
                call.respondText(payload.toString(), ContentType.Application.Json, HttpStatusCode.OK)
            },
            onFailure = { error ->
                val payload = buildJsonObject {
                    put("success", false)
                    put("error", error.message ?: "Memory HTTP status failed.")
                }
                call.respondText(payload.toString(), ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        )
    }
}

private fun String.parseMemoryToolJson(): JsonElement =
    memoryHttpJson.decodeFromString(JsonElement.serializer(), this)
