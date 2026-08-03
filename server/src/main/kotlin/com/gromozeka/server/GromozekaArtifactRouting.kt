package com.gromozeka.server

import com.gromozeka.application.service.ConversationArtifactApplicationService
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.domain.model.ArtifactUpload
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.service.AuthenticationService
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val artifactHttpJson = Json {
    encodeDefaults = true
}

internal fun Routing.gromozekaArtifacts(
    artifactService: ConversationArtifactApplicationService,
    authenticationService: AuthenticationService,
    authorization: GromozekaRemoteAuthorization,
) {
    route("/api/artifacts") {
        install(gromozekaBrowserOriginProtection)

        post {
            val session = call.requireAuthenticated(authenticationService)
            val conversationId = call.request.queryParameters["conversation_id"]
                ?.takeIf(String::isNotBlank)
                ?.let(Conversation::Id)
                ?: return@post call.respondArtifactError(HttpStatusCode.BadRequest, "conversation_id is required")
            val conversation = authorization.requireConversation(
                user = session.principal.user,
                conversationId = conversationId,
                permission = ProjectPermission.WRITE,
            )
            val fileName = call.request.queryParameters["file_name"]
                ?.takeIf(String::isNotBlank)
                ?: return@post call.respondArtifactError(HttpStatusCode.BadRequest, "file_name is required")
            val mediaType = call.request.headers[HttpHeaders.ContentType]
                ?: ContentType.Application.OctetStream.toString()
            val purpose = call.request.queryParameters["purpose"]
                ?.let { runCatching { Artifact.Purpose.valueOf(it) }.getOrNull() }
                ?: Artifact.Purpose.USER_ATTACHMENT
            if (purpose !in CLIENT_ARTIFACT_PURPOSES) {
                return@post call.respondArtifactError(HttpStatusCode.BadRequest, "Invalid client artifact purpose")
            }

            val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (contentLength != null && contentLength > ArtifactLimits.MAX_FILE_BYTES) {
                return@post call.respondArtifactError(HttpStatusCode.PayloadTooLarge, "Artifact is too large")
            }
            val content = call.receiveChannel()
                .readRemaining((ArtifactLimits.MAX_FILE_BYTES + 1).toLong())
                .readByteArray()
            if (content.size > ArtifactLimits.MAX_FILE_BYTES) {
                return@post call.respondArtifactError(HttpStatusCode.PayloadTooLarge, "Artifact is too large")
            }

            val artifact = try {
                artifactService.upload(
                    conversation = conversation,
                    createdByUserId = session.principal.user.id,
                    upload = ArtifactUpload(
                        fileName = fileName,
                        mediaType = mediaType,
                        content = content,
                        purpose = purpose,
                    ),
                )
            } catch (error: IllegalArgumentException) {
                return@post call.respondArtifactError(
                    HttpStatusCode.BadRequest,
                    error.message ?: "Invalid artifact",
                )
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(
                artifactHttpJson.encodeToString(artifact.reference()),
                ContentType.Application.Json,
                HttpStatusCode.Created,
            )
        }

        get("/{artifactId}/content") {
            val session = call.requireAuthenticated(authenticationService)
            val artifactId = call.parameters["artifactId"]
                ?.takeIf(String::isNotBlank)
                ?.let(Artifact::Id)
                ?: return@get call.respondArtifactError(HttpStatusCode.BadRequest, "artifactId is required")
            val artifact = artifactService.find(artifactId)
                ?: return@get call.respondArtifactError(HttpStatusCode.NotFound, "Artifact not found")
            authorization.requireConversation(
                user = session.principal.user,
                conversationId = artifact.conversationId,
                permission = ProjectPermission.READ,
            )
            call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Inline.withParameter(ContentDisposition.Parameters.FileName, artifact.fileName).toString(),
            )
            call.respondBytes(
                bytes = artifactService.read(artifact.id),
                contentType = ContentType.parse(artifact.mediaType),
            )
        }

        delete("/{artifactId}") {
            val session = call.requireAuthenticated(authenticationService)
            val artifactId = call.parameters["artifactId"]
                ?.takeIf(String::isNotBlank)
                ?.let(Artifact::Id)
                ?: return@delete call.respondArtifactError(HttpStatusCode.BadRequest, "artifactId is required")
            val artifact = artifactService.find(artifactId)
                ?: return@delete call.respondArtifactError(HttpStatusCode.NotFound, "Artifact not found")
            authorization.requireConversation(
                user = session.principal.user,
                conversationId = artifact.conversationId,
                permission = ProjectPermission.WRITE,
            )
            when (artifactService.deleteDraft(artifact.conversationId, artifact.id)) {
                ConversationArtifactApplicationService.DraftDeletionResult.DELETED ->
                    call.respondText("", status = HttpStatusCode.NoContent)

                ConversationArtifactApplicationService.DraftDeletionResult.NOT_FOUND ->
                    call.respondArtifactError(HttpStatusCode.NotFound, "Artifact not found")

                ConversationArtifactApplicationService.DraftDeletionResult.ALREADY_COMMITTED ->
                    call.respondArtifactError(HttpStatusCode.Conflict, "Committed artifacts cannot be deleted as drafts")
            }
        }
    }
}

private val CLIENT_ARTIFACT_PURPOSES = setOf(
    Artifact.Purpose.USER_ATTACHMENT,
    Artifact.Purpose.USER_SCREENSHOT,
)

private suspend fun io.ktor.server.application.ApplicationCall.respondArtifactError(
    status: HttpStatusCode,
    message: String,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(
        artifactHttpJson.encodeToString(ArtifactError(message)),
        ContentType.Application.Json,
        status,
    )
}

@kotlinx.serialization.Serializable
private data class ArtifactError(val error: String)
