package com.gromozeka.server

import com.gromozeka.application.service.ContextStateApplicationService
import com.gromozeka.application.service.WorkerContactApplicationService
import com.gromozeka.domain.model.ContextEvent
import com.gromozeka.domain.model.ContextEventAppendResult
import com.gromozeka.domain.model.ContextStateEntry
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerContactKind
import com.gromozeka.domain.model.WorkerContactObservation
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.repository.ContextStateRepository
import com.gromozeka.domain.repository.WorkerContactRepository
import com.gromozeka.domain.repository.WorkerEnrollmentRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class WorkerEventRoutingTest {
    private val credential = "worker-event-credential-long-enough-for-validation"
    private val time = Instant.parse("2026-09-05T00:00:00Z")
    private val eventJson = """{"events":[{"id":"sample","observedAt":"2026-09-05T00:00:00Z","payload":{"type":"battery","levelPercent":50,"charging":false}}]}"""

    @Test
    fun `desktop and Android Workers ingest and deduplicate their own events`() {
        for (platform in listOf("macos", "android")) testApplication {
            val repository = Events()
            val contacts = Contacts()
            val worker = worker(platform)
            val enrollment = Mockito.mock(WorkerEnrollmentRepository::class.java)
            runBlocking { Mockito.`when`(enrollment.authenticateGatewayCredential(hash())).thenReturn(worker) }
            application {
                routing {
                    gromozekaWorkerEvents(
                        WorkerGatewayAuthenticationService(enrollment),
                        ContextStateApplicationService(repository),
                        WorkerContactApplicationService(contacts),
                    )
                }
            }
            val first = client.post("https://localhost/api/worker/events") { header("Authorization", "Bearer $credential"); setBody(eventJson) }
            assertEquals(HttpStatusCode.OK, first.status)
            assertTrue(first.bodyAsText().contains("\"acceptedEventIds\":[\"sample\"]"))
            val retry = client.post("https://localhost/api/worker/events") { header("Authorization", "Bearer $credential"); setBody(eventJson) }
            assertEquals(HttpStatusCode.OK, retry.status)
            assertTrue(retry.bodyAsText().contains("\"duplicateEventIds\":[\"sample\"]"))
            assertEquals(1, repository.events.size)
            assertEquals(ContextEvent.Source.Worker(worker.id), repository.events.single().source)
            assertEquals(worker.subjectUserId, repository.events.single().userId)
            val heartbeat = client.post("https://localhost/api/worker/heartbeat") {
                header("Authorization", "Bearer $credential")
                setBody("""{"contact":{"requestId":"heartbeat","sentAt":"2026-09-05T00:00:00Z","appState":"BACKGROUND","appVersion":"test","pendingEventCount":0}}""")
            }
            assertEquals(HttpStatusCode.OK, heartbeat.status)
            assertEquals(WorkerContactKind.HEARTBEAT, contacts.observations.last().kind)
            assertEquals(worker.subjectUserId, contacts.observations.last().subjectUserId)
            assertEquals(0, contacts.observations.last().eventCount)
        }
    }

    @Test
    fun `authentication binding and bounded payload are required`() = testApplication {
        val repository = Events()
        val enrollment = Mockito.mock(WorkerEnrollmentRepository::class.java)
        runBlocking { Mockito.`when`(enrollment.authenticateGatewayCredential(hash())).thenReturn(worker("android")) }
        application {
            routing {
                gromozekaWorkerEvents(
                    WorkerGatewayAuthenticationService(enrollment),
                    ContextStateApplicationService(repository),
                    WorkerContactApplicationService(Contacts()),
                )
            }
        }
        assertEquals(HttpStatusCode.UpgradeRequired, client.post("/api/worker/events") { setBody(eventJson) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("https://localhost/api/worker/events") { setBody(eventJson) }.status)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("https://localhost/api/worker/events") {
            header("Authorization", "Bearer $credential"); setBody("x".repeat(256 * 1024 + 1))
        }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("https://localhost/api/worker/events") {
            header("Authorization", "Bearer $credential"); setBody(eventJson.replace("sample", "invalid/id"))
        }.status)
        runBlocking { Mockito.`when`(enrollment.authenticateGatewayCredential(hash())).thenReturn(worker("macos").copy(subjectUserId = null)) }
        assertEquals(HttpStatusCode.Forbidden, client.post("https://localhost/api/worker/events") { header("Authorization", "Bearer $credential"); setBody(eventJson) }.status)
        assertTrue(repository.events.isEmpty())
    }

    private fun worker(platform: String) = WorkerResource(ConversationRuntimeWorkerId("worker-$platform"), "Worker", User.Id("owner"), platform, User.Id("subject"), false, WorkerResource.Status.ACTIVE, time, time)
    private fun hash() = MessageDigest.getInstance("SHA-256").digest(credential.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private class Contacts : WorkerContactRepository {
        val observations = mutableListOf<WorkerContactObservation>()
        override suspend fun record(observation: WorkerContactObservation) {
            observations += observation
        }
    }

    private class Events : ContextStateRepository {
        val events = mutableListOf<ContextEvent>()
        override suspend fun append(events: List<ContextEvent>): ContextEventAppendResult {
            val existing = this.events.map { it.id }.toSet()
            this.events += events.filter { it.id !in existing }
            return ContextEventAppendResult(events.filter { it.id !in existing }.mapTo(linkedSetOf()) { it.id }, events.filter { it.id in existing }.mapTo(linkedSetOf()) { it.id })
        }
        override suspend fun currentState(userId: User.Id, subject: ContextEvent.Subject?): List<ContextStateEntry> = error("Not used")
        override suspend fun history(userId: User.Id, subject: ContextEvent.Subject?, from: Instant?, to: Instant?, limit: Int): List<ContextEvent> = error("Not used")
    }
}
