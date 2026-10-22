package com.gromozeka.server

import com.gromozeka.domain.model.WorkerPermission
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkerAccessDeniedException
import com.gromozeka.domain.service.WorkerAccessService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.CancellationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@RestController
internal class GromozekaInteractiveWorkerAccessRouting(
    private val interactiveAccessService: InteractiveWorkerAccessService,
    private val authenticationService: AuthenticationService,
    private val workerAccessService: WorkerAccessService,
    private val settings: ServerHttpSettings,
) {
    @GetMapping("/api/workers/{workerId}/interactive-access")
    suspend fun open(@PathVariable workerId: String, request: HttpServletRequest, response: HttpServletResponse) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        val principal = request.requireAuthenticated(authenticationService).principal
        if (!request.isSecureTransport(settings)) return response.writeHttpError(HttpStatus.UPGRADE_REQUIRED, "Interactive access requires HTTPS")
        val id = workerId.trim().takeIf(String::isNotEmpty)?.let(::ConversationRuntimeWorkerId)
        if (id == null || interactiveAccessService.openUrl(id) == null) return response.writeText("Not found", HttpStatus.NOT_FOUND)
        try { workerAccessService.requirePermission(principal.user, id, WorkerPermission.USE) }
        catch (_: WorkerAccessDeniedException) { return response.writeText("Not found", HttpStatus.NOT_FOUND) }
        response.status = HttpStatus.FOUND.value()
        response.setHeader(HttpHeaders.LOCATION, interactiveAccessService.issueRedirect(id))
    }

    @PostMapping("/internal/dcv-auth")
    suspend fun dcv(request: HttpServletRequest, response: HttpServletResponse) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        val parameters = try {
            val text = request.readBoundedBody(MAX_DCV_AUTH_REQUEST_BYTES).decodeToString(throwOnInvalidSequence = true)
            val entries = text.split('&').map { pair ->
                val fields = pair.split('=', limit = 2)
                URLDecoder.decode(fields.first(), StandardCharsets.UTF_8) to
                    URLDecoder.decode(fields.getOrElse(1) { "" }, StandardCharsets.UTF_8)
            }
            require(entries.map { it.first }.distinct().size == entries.size) { "Duplicate form key" }
            entries.toMap()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        val username = interactiveAccessService.consumeDcvGrant(parameters?.get("sessionId"), parameters?.get("authenticationToken"))
        val body = if (username == null) """<auth result="no"><message>Authentication denied</message></auth>"""
            else """<auth result="yes"><username>${username.xmlText()}</username></auth>"""
        response.writeText(body, contentType = "application/xml")
    }
}

private fun String.xmlText(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&apos;")
private const val MAX_DCV_AUTH_REQUEST_BYTES = 4 * 1024
