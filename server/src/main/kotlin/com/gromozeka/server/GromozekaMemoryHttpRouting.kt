package com.gromozeka.server

import com.gromozeka.application.service.MemoryToolApplicationService
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.service.AuthenticationService
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

fun Routing.gromozekaMemoryHttp(
    memoryToolApplicationService: MemoryToolApplicationService,
    authenticationService: AuthenticationService,
) {
    get("/memory/status") {
        val principal = call.authenticateOrNull(authenticationService)
        if (principal == null) {
            call.respondText(
                """{"success":false,"error":"Authentication required"}""",
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
            return@get
        }
        val runId = call.request.queryParameters["run_id"]?.trim()?.takeIf { it.isNotEmpty() }
        val includeChildren = call.request.queryParameters["include_children"]?.toBooleanStrictOrNull() ?: true
        val maxDepth = call.request.queryParameters["max_depth"]?.toIntOrNull() ?: 4

        val response = runCatching {
            val namespace = MemoryNamespace.forUser(principal.user.id)
            buildJsonObject {
                put("success", true)
                put(
                    "queue",
                    memoryToolApplicationService.memoryQueueStatus(namespace).parseMemoryToolJson(),
                )
                put(
                    "namespaces",
                    memoryToolApplicationService.listNamespaces(namespace).parseMemoryToolJson(),
                )
                if (runId != null) {
                    put(
                        "run",
                        memoryToolApplicationService.memoryRunStatus(
                            namespace = namespace,
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
