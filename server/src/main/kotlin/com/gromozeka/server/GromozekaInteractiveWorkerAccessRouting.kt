package com.gromozeka.server

import com.gromozeka.domain.model.WorkerPermission
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkerAccessDeniedException
import com.gromozeka.domain.service.WorkerAccessService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

internal fun Routing.gromozekaInteractiveWorkerAccess(
    interactiveAccessService: InteractiveWorkerAccessService,
    authenticationService: AuthenticationService,
    workerAccessService: WorkerAccessService,
) {
    get("/api/workers/{workerId}/interactive-access") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val authenticatedUser = call.authenticateOrNull(authenticationService)
        if (authenticatedUser == null) {
            call.respondText(
                """{"error":"Authentication required"}""",
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
            return@get
        }
        if (!call.isSecureTransport()) {
            call.respondText(
                """{"error":"Interactive access requires HTTPS"}""",
                ContentType.Application.Json,
                HttpStatusCode.UpgradeRequired,
            )
            return@get
        }
        val workerId = call.parameters["workerId"]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::ConversationRuntimeWorkerId)
        if (workerId == null || interactiveAccessService.openUrl(workerId) == null) {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return@get
        }
        try {
            workerAccessService.requirePermission(
                actor = authenticatedUser.user,
                workerId = workerId,
                permission = WorkerPermission.USE,
            )
        } catch (_: WorkerAccessDeniedException) {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return@get
        }
        call.respondRedirect(interactiveAccessService.issueRedirect(workerId), permanent = false)
    }

    post("/internal/dcv-auth") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > MAX_DCV_AUTH_REQUEST_BYTES) {
            call.respondDcvAuthentication(null)
            return@post
        }
        val parameters = runCatching { call.receiveParameters() }.getOrNull()
        val username = interactiveAccessService.consumeDcvGrant(
            sessionId = parameters?.get("sessionId"),
            authenticationToken = parameters?.get("authenticationToken"),
        )
        call.respondDcvAuthentication(username)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondDcvAuthentication(username: String?) {
    val body = if (username == null) {
        """<auth result="no"><message>Authentication denied</message></auth>"""
    } else {
        """<auth result="yes"><username>${username.xmlText()}</username></auth>"""
    }
    respondText(body, ContentType.Application.Xml, HttpStatusCode.OK)
}

private fun String.xmlText(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

private const val MAX_DCV_AUTH_REQUEST_BYTES = 4 * 1024
