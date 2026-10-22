package com.gromozeka.server

import com.gromozeka.application.service.ConversationHistoryReadService
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationHistoryPageRequest
import com.gromozeka.domain.model.ConversationMessageSelection
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.ProjectAccessDeniedException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/conversations/{conversationId}/history")
internal class GromozekaHistoryRouting(
    private val history: ConversationHistoryReadService,
    private val authentication: AuthenticationService,
    private val authorization: GromozekaRemoteAuthorization,
) {
    @GetMapping
    suspend fun page(@PathVariable conversationId: String, request: HttpServletRequest, response: HttpServletResponse) {
        val id = requireRead(conversationId, request, response)
        val query = try {
            request.getParameter("request")?.let { httpJson.decodeFromString<ConversationHistoryPageRequest>(it) }
                ?: ConversationHistoryPageRequest()
        } catch (_: IllegalArgumentException) { response.status = HttpStatus.BAD_REQUEST.value(); return }
        response.writeJson(history.page(id, query))
    }

    @GetMapping("/messages/{messageId}")
    suspend fun message(@PathVariable conversationId: String, @PathVariable messageId: String,
        request: HttpServletRequest, response: HttpServletResponse) {
        val id = requireRead(conversationId, request, response)
        response.writeJson(history.message(id, Conversation.Message.Id(messageId)))
    }

    @GetMapping("/selection/{kind}")
    suspend fun selection(@PathVariable conversationId: String, @PathVariable kind: String,
        request: HttpServletRequest, response: HttpServletResponse) {
        val id = requireRead(conversationId, request, response)
        val selection = ConversationMessageSelection.entries.firstOrNull { it.name == kind }
            ?: run { response.status = HttpStatus.BAD_REQUEST.value(); return }
        response.writeJson(history.selectedIds(id, selection))
    }

    @GetMapping("/latest-user-message")
    suspend fun latest(@PathVariable conversationId: String, request: HttpServletRequest, response: HttpServletResponse) {
        response.writeJson(history.latestUserMessage(requireRead(conversationId, request, response)))
    }

    private suspend fun requireRead(value: String, request: HttpServletRequest, response: HttpServletResponse): Conversation.Id {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        val session = request.requireAuthenticated(authentication)
        val id = Conversation.Id(value)
        try { authorization.requireConversation(session.principal.user, id, ProjectPermission.READ) }
        catch (_: ProjectAccessDeniedException) { throw HttpAuthenticationException(HttpStatus.FORBIDDEN, "Conversation access denied") }
        return id
    }
}
