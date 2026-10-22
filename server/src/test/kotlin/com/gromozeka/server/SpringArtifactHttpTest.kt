package com.gromozeka.server

import com.gromozeka.application.service.ConversationArtifactApplicationService
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.ArtifactUpload
import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.UserSession
import com.gromozeka.domain.service.AuthenticationService
import kotlinx.coroutines.runBlocking
import org.mockito.Answers
import org.mockito.Mockito
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SpringArtifactHttpTest {
    @Test
    fun `upload metadata comes only from query not a form body`() = runBlocking<Unit> {
        val fixture = ArtifactFixture()
        fixture.http().use { http ->
            // Servlet parameters normally combine URI query and parsed form fields.
            // A raw artifact body must never supply its own upload metadata.
            val response = http.perform(post("/api/artifacts").secureTestRequest("synthetic-session")
                .queryParam("conversation_id", fixture.artifact.conversationId.value)
                .param("file_name", "body-only.txt")
                .contentType("application/x-www-form-urlencoded").content("file_name=body-only.txt&raw=payload"))
            assertEquals(400, response.status)
            assertTrue(fixture.uploads.isEmpty())
        }
    }

    @Test
    fun `raw upload and authenticated download preserve exact bytes Unicode name and authorization`() = runBlocking<Unit> {
        val fixture = ArtifactFixture()
        val name = "שלום + & file.bin"
        val raw = byteArrayOf(0, 1, 2, 0xFF.toByte(), 10, 13, 0)
        fixture.bytes = raw
        fixture.http().use { http ->
            val response = http.perform(post("/api/artifacts").secureTestRequest("synthetic-session")
                .queryParam("conversation_id", fixture.artifact.conversationId.value)
                .with {
                    // Model the actual encoded URI, not MockMvc's independent parameter map:
                    // literal '+' is %2B on the wire, whereas raw '+' means a space.
                    it.queryString = "conversation_id=${fixture.artifact.conversationId.value}&file_name=" +
                        java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
                    it
                }.contentType("application/octet-stream").content(raw))
            assertEquals(201, response.status)
            assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL))
            val upload = fixture.uploads.single()
            assertEquals(name, upload.fileName)
            assertContentEquals(raw, upload.content)
            assertEquals(Artifact.Purpose.USER_ATTACHMENT, upload.purpose)
            assertEquals(listOf(ProjectPermission.WRITE), fixture.permissions)
            val denied = http.perform(get("/api/artifacts/${fixture.artifact.id.value}/content").secureTestRequest())
            assertEquals(401, denied.status)
            assertEquals(0, fixture.reads)
            val download = http.perform(get("/api/artifacts/${fixture.artifact.id.value}/content").secureTestRequest("synthetic-session"))
            assertEquals(200, download.status)
            assertContentEquals(raw, download.contentAsByteArray)
            assertEquals("application/octet-stream", download.contentType)
            assertEquals("private, max-age=31536000, immutable", download.getHeader(HttpHeaders.CACHE_CONTROL))
            assertTrue(download.getHeader(HttpHeaders.CONTENT_DISPOSITION).orEmpty().startsWith("inline;"))
            assertEquals(listOf(ProjectPermission.WRITE, ProjectPermission.READ), fixture.permissions)
        }
    }

    @Test
    fun `cross origin upload and invalid percent encoding never reach storage`() = runBlocking<Unit> {
        val fixture = ArtifactFixture()
        fixture.http().use { http ->
            val cross = http.perform(post("/api/artifacts").secureTestRequest("synthetic-session")
                .header("Origin", "https://foreign.invalid").content(byteArrayOf(1)))
            assertEquals(403, cross.status)
            val malformed = http.perform(post("/api/artifacts").secureTestRequest("synthetic-session")
                .with { it.queryString = "conversation_id=synthetic-conversation&file_name=%ZZ"; it }.content(byteArrayOf(1)))
            assertEquals(400, malformed.status)
            assertFalse(malformed.contentAsString.contains("%ZZ"))
            assertTrue(fixture.uploads.isEmpty())
        }
    }

    private class ArtifactFixture {
        private val user = testControlMcpCaller().user
        private val conversation = Mockito.mock(Conversation::class.java)
        val artifact = Artifact(Artifact.Id("synthetic-artifact"), Project.Id("synthetic-project"),
            Conversation.Id("synthetic-conversation"), user.id, "שלום file.bin", "application/octet-stream", 7,
            Artifact.ContentSource.Managed("0".repeat(64)), Artifact.Purpose.USER_ATTACHMENT, createdAt = user.createdAt)
        val uploads = mutableListOf<ArtifactUpload>()
        val permissions = mutableListOf<ProjectPermission>()
        var bytes = byteArrayOf(1, 2, 3)
        var reads = 0
        private val authentication = Mockito.mock(AuthenticationService::class.java) { call ->
            if (call.method.name == "authenticate" && call.arguments.first() == "synthetic-session")
                AuthenticatedUser(user, UserSession.Id("synthetic-session"))
            else Answers.RETURNS_DEFAULTS.answer(call)
        }
        private val authorization = Mockito.mock(GromozekaRemoteAuthorization::class.java) { call ->
            if (call.method.name.startsWith("requireConversation")) {
                permissions += call.arguments.filterIsInstance<ProjectPermission>().single()
                conversation
            } else Answers.RETURNS_DEFAULTS.answer(call)
        }
        private val storage = Mockito.mock(ConversationArtifactApplicationService::class.java) { call ->
            when {
                call.method.name.startsWith("upload") -> {
                    assertSame(conversation, call.arguments.first())
                    uploads += call.arguments.filterIsInstance<ArtifactUpload>().single()
                    artifact
                }
                call.method.name.startsWith("find") -> artifact
                call.method.name.startsWith("read") -> { reads++; bytes }
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        fun http() = SpringHttpTestFixture(GromozekaArtifactRouting(storage, authentication, authorization), authentication = authentication)
    }
}
