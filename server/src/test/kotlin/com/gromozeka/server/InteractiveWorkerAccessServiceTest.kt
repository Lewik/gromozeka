package com.gromozeka.server

import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.model.UserSession
import com.gromozeka.domain.model.WorkerPermission
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkerAccessService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant as KotlinInstant
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.mockito.Mockito
import java.net.URI
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InteractiveWorkerAccessServiceTest {
    @Test
    fun `stable link contains no credential and DCV handoff is single use`() {
        val service = service()

        val openUrl = service.openUrl(workerId)
        val redirect = service.issueRedirect(workerId)
        val token = redirect.authenticationToken()

        assertEquals(
            "https://runtime.example/api/workers/aws-computer/interactive-access",
            openUrl,
        )
        assertFalse(openUrl!!.contains("authToken"))
        assertContains(redirect, "https://runtime.example:8443/?authToken=")
        assertEquals("gromozeka-computer", service.consumeDcvGrant("aws-computer", token))
        assertNull(service.consumeDcvGrant("aws-computer", token))
    }

    @Test
    fun `wrong session does not consume a grant and expired grants are rejected`() {
        val clock = MutableClock(Instant.parse("2026-08-05T12:00:00Z"))
        val service = service(clock)
        val token = service.issueRedirect(workerId).authenticationToken()

        assertNull(service.consumeDcvGrant("other-session", token))
        assertEquals("gromozeka-computer", service.consumeDcvGrant("aws-computer", token))

        val expiredToken = service.issueRedirect(workerId).authenticationToken()
        clock.advance(Duration.ofSeconds(61))
        assertNull(service.consumeDcvGrant("aws-computer", expiredToken))
    }

    @Test
    fun `authenticated Worker user is redirected through a fresh DCV grant`() = runBlocking {
        val service = service()
        val authenticationService = Mockito.mock(AuthenticationService::class.java)
        val workerAccessService = Mockito.mock(WorkerAccessService::class.java)
        val user = testControlMcpContext().user
        val worker = worker(user.id)
        Mockito.`when`(authenticationService.authenticate("session-token"))
            .thenReturn(AuthenticatedUser(user, UserSession.Id("session")))
        Mockito.`when`(
            workerAccessService.requirePermission(
                actor = user,
                workerId = workerId,
                permission = WorkerPermission.USE,
                projectId = null,
            )
        ).thenReturn(worker)

        testApplication {
            application {
                routing {
                    gromozekaInteractiveWorkerAccess(service, authenticationService, workerAccessService)
                }
            }
            val noRedirectClient = createClient { followRedirects = false }
            val response = noRedirectClient.get("https://localhost/api/workers/aws-computer/interactive-access") {
                header(HttpHeaders.Cookie, "$SESSION_COOKIE_NAME=session-token")
            }

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            val redirect = requireNotNull(response.headers[HttpHeaders.Location])
            assertEquals(
                "gromozeka-computer",
                service.consumeDcvGrant("aws-computer", redirect.authenticationToken()),
            )
        }
    }

    @Test
    fun `DCV verifier returns Amazon DCV XML and consumes the grant`() = testApplication {
        val service = service()
        val token = service.issueRedirect(workerId).authenticationToken()
        application {
            routing {
                gromozekaInteractiveWorkerAccess(
                    service,
                    Mockito.mock(AuthenticationService::class.java),
                    Mockito.mock(WorkerAccessService::class.java),
                )
            }
        }

        val first = client.post("/internal/dcv-auth") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("sessionId=aws-computer&authenticationToken=$token&clientAddress=100.64.0.1")
        }
        val second = client.post("/internal/dcv-auth") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("sessionId=aws-computer&authenticationToken=$token&clientAddress=100.64.0.1")
        }

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(ContentType.Application.Xml, first.contentType()?.withoutParameters())
        assertEquals(
            """<auth result="yes"><username>gromozeka-computer</username></auth>""",
            first.bodyAsText(),
        )
        assertEquals(
            """<auth result="no"><message>Authentication denied</message></auth>""",
            second.bodyAsText(),
        )
    }

    @Test
    fun `interactive access control tool returns only the stable link`() = runBlocking {
        val service = service()
        val workerAccessService = Mockito.mock(WorkerAccessService::class.java)
        val context = testControlMcpContext()
        Mockito.`when`(
            workerAccessService.requirePermission(
                actor = context.user,
                workerId = workerId,
                permission = WorkerPermission.USE,
                projectId = null,
            )
        ).thenReturn(worker(context.user.id))
        val provider = ControlMcpInteractiveWorkerAccessTools(service, workerAccessService)

        val result = provider.tools.single().invokeStructured(
            context,
            kotlinx.serialization.json.buildJsonObject { put("workerId", workerId.value) },
        )
        val openUrl = result.getValue("result").jsonObject.getValue("openUrl").jsonPrimitive.content

        assertEquals("grz_worker_interactive_access_get", provider.tools.single().definition.name)
        assertEquals(service.openUrl(workerId), openUrl)
        assertFalse(openUrl.contains("authToken"))
        assertTrue(provider.tools.single().definition.annotations?.readOnlyHint == true)
    }

    private fun service(clock: Clock = Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneOffset.UTC)) =
        InteractiveWorkerAccessService(
            configuration = InteractiveWorkerAccessConfiguration(
                DcvInteractiveWorkerAccessTarget(
                    workerId = workerId,
                    serverBaseUrl = "https://runtime.example",
                    dcvBaseUrl = "https://runtime.example:8443",
                    sessionId = "aws-computer",
                    username = "gromozeka-computer",
                )
            ),
            clock = clock,
            secureRandom = SecureRandom(),
        )

    private fun worker(ownerId: com.gromozeka.domain.model.User.Id) = WorkerResource(
        id = workerId,
        displayName = "AWS Computer",
        ownerUserId = ownerId,
        runtimeWideAccess = false,
        status = WorkerResource.Status.ACTIVE,
        createdAt = KotlinInstant.parse("2026-08-05T12:00:00Z"),
        updatedAt = KotlinInstant.parse("2026-08-05T12:00:00Z"),
    )

    private fun String.authenticationToken(): String =
        requireNotNull(URI(this).rawQuery)
            .substringAfter("authToken=")

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private companion object {
        val workerId = ConversationRuntimeWorkerId("aws-computer")
    }
}
