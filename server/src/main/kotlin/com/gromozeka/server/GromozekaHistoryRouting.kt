package com.gromozeka.server

import com.gromozeka.application.service.ConversationHistoryReadService
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationHistoryPageRequest
import com.gromozeka.domain.model.ConversationMessageSelection
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.ProjectAccessDeniedException
import io.ktor.server.application.ApplicationCall
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json

private val historyJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

internal fun Routing.gromozekaHistory(
    history: ConversationHistoryReadService,
    authentication: AuthenticationService,
    authorization: GromozekaRemoteAuthorization,
) {
    route("/api/conversations/{conversationId}/history") {
        install(gromozekaBrowserOriginProtection)
        get {
            val conversationId = call.requireHistoryRead(authentication, authorization)
            val request = try {
                call.request.queryParameters["request"]?.let { historyJson.decodeFromString<ConversationHistoryPageRequest>(it) }
                    ?: ConversationHistoryPageRequest()
            } catch (error: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(historyJson.encodeToString(history.page(conversationId, request)), ContentType.Application.Json)
        }
        get("/messages/{messageId}") {
            val conversationId = call.requireHistoryRead(authentication, authorization)
            val messageId = Conversation.Message.Id(requireNotNull(call.parameters["messageId"]))
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(historyJson.encodeToString(history.message(conversationId, messageId)), ContentType.Application.Json)
        }
        get("/selection/{kind}") {
            val conversationId = call.requireHistoryRead(authentication, authorization)
            val kind = ConversationMessageSelection.entries.firstOrNull { it.name == call.parameters["kind"] }
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(historyJson.encodeToString(history.selectedIds(conversationId, kind)), ContentType.Application.Json)
        }
        get("/latest-user-message") {
            val conversationId = call.requireHistoryRead(authentication, authorization)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(historyJson.encodeToString(history.latestUserMessage(conversationId)), ContentType.Application.Json)
        }
    }
}

private suspend fun ApplicationCall.requireHistoryRead(
    authentication: AuthenticationService,
    authorization: GromozekaRemoteAuthorization,
): Conversation.Id {
    val session = try {
        requireAuthenticated(authentication)
    } catch (_: MissingAuthenticationException) {
        throw HttpAuthenticationException(HttpStatusCode.Unauthorized, "Authentication required")
    }
    val conversationId = Conversation.Id(requireNotNull(parameters["conversationId"]))
    try {
        authorization.requireConversation(session.principal.user, conversationId, ProjectPermission.READ)
    } catch (_: ProjectAccessDeniedException) {
        throw HttpAuthenticationException(HttpStatusCode.Forbidden, "Conversation access denied")
    }
    return conversationId
}
