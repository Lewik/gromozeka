package com.gromozeka.server

import com.gromozeka.application.service.ConversationArtifactApplicationService
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.domain.model.ArtifactUpload
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.service.AuthenticationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets

@RestController
@RequestMapping("/api/artifacts")
internal class GromozekaArtifactRouting(
    private val artifacts: ConversationArtifactApplicationService,
    private val authentication: AuthenticationService,
    private val authorization: GromozekaRemoteAuthorization,
) {
    @PostMapping
    suspend fun upload(request: HttpServletRequest, response: HttpServletResponse) {
        val session = request.requireAuthenticated(authentication)
        val query = try { request.artifactQueryParameters() }
        catch (_: IllegalArgumentException) { return response.writeHttpError(HttpStatus.BAD_REQUEST, "Invalid artifact query") }
        val id = query["conversation_id"]?.takeIf(String::isNotBlank)?.let(Conversation::Id)
            ?: return response.writeHttpError(HttpStatus.BAD_REQUEST, "conversation_id is required")
        val conversation = authorization.requireConversation(session.principal.user, id, ProjectPermission.WRITE)
        val name = query["file_name"]?.takeIf(String::isNotBlank)
            ?: return response.writeHttpError(HttpStatus.BAD_REQUEST, "file_name is required")
        val mediaType = request.contentType ?: MediaType.APPLICATION_OCTET_STREAM_VALUE
        val purpose = query["purpose"]?.let { runCatching { Artifact.Purpose.valueOf(it) }.getOrNull() }
            ?: Artifact.Purpose.USER_ATTACHMENT
        if (purpose !in CLIENT_ARTIFACT_PURPOSES) return response.writeHttpError(HttpStatus.BAD_REQUEST, "Invalid client artifact purpose")
        val content = try { request.readBoundedBody(ArtifactLimits.MAX_FILE_BYTES) }
        catch (_: HttpRequestBodyTooLarge) { return response.writeHttpError(HttpStatus.PAYLOAD_TOO_LARGE, "Artifact is too large") }
        val artifact = try {
            artifacts.upload(conversation = conversation, createdByUserId = session.principal.user.id,
                upload = ArtifactUpload(fileName = name, mediaType = mediaType, content = content, purpose = purpose))
        } catch (error: IllegalArgumentException) {
            return response.writeHttpError(HttpStatus.BAD_REQUEST, error.message ?: "Invalid artifact")
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        response.writeJson(artifact.reference(), HttpStatus.CREATED)
    }

    @GetMapping("/{artifactId}/content")
    suspend fun content(@PathVariable artifactId: String, request: HttpServletRequest, response: HttpServletResponse) {
        val session = request.requireAuthenticated(authentication)
        val artifact = artifacts.find(Artifact.Id(artifactId))
            ?: return response.writeHttpError(HttpStatus.NOT_FOUND, "Artifact not found")
        authorization.requireConversation(session.principal.user, artifact.conversationId, ProjectPermission.READ)
        val bytes = try { artifacts.read(artifact.id) }
        catch (error: com.gromozeka.domain.service.ArtifactContentUnavailableException) {
            return response.writeHttpError(HttpStatus.BAD_GATEWAY, error.reason)
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, if (artifact.source is Artifact.ContentSource.Managed)
            "private, max-age=31536000, immutable" else "private, no-store")
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
            .filename(artifact.fileName, StandardCharsets.UTF_8).build().toString())
        response.contentType = MediaType.parseMediaType(artifact.mediaType).toString()
        response.setContentLengthLong(bytes.size.toLong())
        runInterruptible(Dispatchers.IO) { response.outputStream.write(bytes) }
    }

    @DeleteMapping("/{artifactId}")
    suspend fun delete(@PathVariable artifactId: String, request: HttpServletRequest, response: HttpServletResponse) {
        val session = request.requireAuthenticated(authentication)
        val artifact = artifacts.find(Artifact.Id(artifactId))
            ?: return response.writeHttpError(HttpStatus.NOT_FOUND, "Artifact not found")
        authorization.requireConversation(session.principal.user, artifact.conversationId, ProjectPermission.WRITE)
        when (artifacts.deleteDraft(artifact.conversationId, artifact.id)) {
            ConversationArtifactApplicationService.DraftDeletionResult.DELETED -> response.status = HttpStatus.NO_CONTENT.value()
            ConversationArtifactApplicationService.DraftDeletionResult.NOT_FOUND -> response.writeHttpError(HttpStatus.NOT_FOUND, "Artifact not found")
            ConversationArtifactApplicationService.DraftDeletionResult.ALREADY_COMMITTED ->
                response.writeHttpError(HttpStatus.CONFLICT, "Committed artifacts cannot be deleted as drafts")
        }
    }
}

/** Servlet getParameter also parses form bodies, consuming bytes of a raw file upload. */
private fun HttpServletRequest.artifactQueryParameters(): Map<String, String> = buildMap {
    queryString.orEmpty().split('&').filter(String::isNotEmpty).forEach { parameter ->
        val parts = parameter.split('=', limit = 2)
        val name = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
        val value = java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
        putIfAbsent(name, value)
    }
}

private val CLIENT_ARTIFACT_PURPOSES = setOf(Artifact.Purpose.USER_ATTACHMENT, Artifact.Purpose.USER_SCREENSHOT)
