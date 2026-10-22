package com.gromozeka.server

import com.gromozeka.application.service.MemoryToolApplicationService
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.service.AuthenticationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

private val memoryHttpJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

@RestController
internal class GromozekaMemoryHttpRouting(
    private val memory: MemoryToolApplicationService,
    private val authentication: AuthenticationService,
) {
    @GetMapping("/memory/status")
    suspend fun status(request: HttpServletRequest, response: HttpServletResponse) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        val principal = request.requireAuthenticated(authentication).principal
        val runId = request.getParameter("run_id")?.trim()?.takeIf(String::isNotEmpty)
        val includeChildren = request.getParameter("include_children")?.toBooleanStrictOrNull() ?: true
        val maxDepth = request.getParameter("max_depth")?.toIntOrNull() ?: 4
        try {
            val namespace = MemoryNamespace.forUser(principal.user.id)
            val payload = buildJsonObject {
                put("success", true)
                put("queue", memory.memoryQueueStatus(namespace).parseMemoryToolJson())
                put("namespaces", memory.listNamespaces(namespace).parseMemoryToolJson())
                if (runId != null) put("run", memory.memoryRunStatus(namespace, runId, includeChildren, maxDepth).parseMemoryToolJson())
            }
            response.writeText(payload.toString(), contentType = "application/json")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            response.writeText(buildJsonObject {
                put("success", false); put("error", error.message ?: "Memory HTTP status failed.")
            }.toString(), HttpStatus.INTERNAL_SERVER_ERROR, "application/json")
        }
    }
}

private fun String.parseMemoryToolJson(): JsonElement = memoryHttpJson.decodeFromString(JsonElement.serializer(), this)
