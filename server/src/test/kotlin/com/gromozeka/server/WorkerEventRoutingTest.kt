package com.gromozeka.server

import com.gromozeka.application.service.ContextStateApplicationService
import com.gromozeka.application.service.WorkerContactApplicationService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.ContextStateRepository
import com.gromozeka.domain.repository.WorkerContactRepository
import com.gromozeka.domain.repository.WorkerEnrollmentRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class WorkerEventRoutingTest {
    private val credential = "worker-event-credential-long-enough-for-validation"
    private val time = Instant.parse("2026-09-05T00:00:00Z")
    private val eventJson = """{"events":[{"id":"sample","observedAt":"2026-09-05T00:00:00Z","payload":{"type":"battery","levelPercent":50,"charging":false}}]}"""

    private fun fixture(repository: Events, contacts: Contacts, enrollment: WorkerEnrollmentRepository): SpringHttpTestFixture {
        val authentication = WorkerGatewayAuthenticationService(enrollment)
        return SpringHttpTestFixture(WorkerEventRouting(authentication, ContextStateApplicationService(repository),
            WorkerContactApplicationService(contacts), ServerHttpSettings()), workers = authentication)
    }
    private fun eventRequest(body: String, path: String = "/api/worker/events") = post(path).secureTestRequest()
        .header("Authorization", "Bearer $credential").contentType("application/json").content(body)

    @Test
    fun `desktop and Android Workers ingest and deduplicate their own events`() = runBlocking<Unit> {
        for (platform in listOf("macos", "android")) {
            val repository = Events()
            val contacts = Contacts()
            val worker = worker(platform)
            val enrollment = Mockito.mock(WorkerEnrollmentRepository::class.java)
            Mockito.`when`(enrollment.authenticateGatewayCredential(hash())).thenReturn(worker)
            fixture(repository, contacts, enrollment).use { http ->
                val first = http.perform(eventRequest(eventJson))
                assertEquals(200, first.status)
                assertTrue(first.contentAsString.contains("\"acceptedEventIds\":[\"sample\"]"))
                val retry = http.perform(eventRequest(eventJson))
                assertEquals(200, retry.status)
                assertTrue(retry.contentAsString.contains("\"duplicateEventIds\":[\"sample\"]"))
                assertEquals(1, repository.events.size)
                assertEquals(ContextEvent.Source.Worker(worker.id), repository.events.single().source)
                assertEquals(worker.subjectUserId, repository.events.single().userId)
                val locationJson = """{"events":[{"id":"location","observedAt":"2026-09-05T00:00:00Z","payload":{"type":"location","latitude":32.0,"longitude":34.8,"accuracyMeters":15.0,"cause":"LIVE_TRACKING"}}]}"""
                assertEquals(200, http.perform(eventRequest(locationJson)).status)
                val recorded = repository.events.last()
                assertEquals(time, recorded.observedAt)
                assertEquals(worker.subjectUserId, recorded.userId)
                assertEquals(15.0, ((recorded.payload as ContextEvent.Payload.Device).event as DeviceStateEvent.Location).accuracyMeters)
                val heartbeat = http.perform(eventRequest("""{"contact":{"requestId":"heartbeat","sentAt":"2026-09-05T00:00:00Z","appState":"BACKGROUND","appVersion":"test","pendingEventCount":0}}""", "/api/worker/heartbeat"))
                assertEquals(200, heartbeat.status)
                assertEquals(WorkerContactKind.HEARTBEAT, contacts.observations.last().kind)
                assertEquals(worker.subjectUserId, contacts.observations.last().subjectUserId)
                assertEquals(0, contacts.observations.last().eventCount)
            }
        }
    }

    @Test
    fun `mixed Android telemetry is authenticated retained and deduplicated with device attribution`() = runBlocking<Unit> {
        val repository = Events()
        val enrollment = Mockito.mock(WorkerEnrollmentRepository::class.java)
        val worker = worker("android")
        Mockito.`when`(enrollment.authenticateGatewayCredential(hash())).thenReturn(worker)
        val body = """{"events":[
            {"id":"app","observedAt":"2026-09-05T00:00:00Z","payload":{"type":"app_activity","packageName":"test.game","transition":"RESUMED"}},
            {"id":"screen","observedAt":"2026-09-05T00:00:01Z","payload":{"type":"screen_state","interactive":false,"keyguardShowing":true,"deviceLocked":true}},
            {"id":"metadata","observedAt":"2026-09-05T00:00:02Z","payload":{"type":"application_info","packageName":"test.game","label":"Game","metadataAvailable":true}},
            {"id":"coverage","observedAt":"2026-09-05T00:00:02Z","payload":{"type":"collection_status","source":"APP_USAGE","state":"ACTIVE","queriedFrom":"2026-09-05T00:00:00Z","queriedThrough":"2026-09-05T00:00:02Z"}},
            {"id":"geo","observedAt":"2026-09-05T00:00:00Z","payload":{"type":"location","latitude":32.0,"longitude":34.8,"accuracyMeters":10.0,"cause":"LIVE_TRACKING"}},
            {"id":"lock","observedAt":"2026-09-05T00:00:01Z","payload":{"type":"device_usage_transition","transition":"KEYGUARD_SHOWN"}},
            {"id":"gap","observedAt":"2026-09-05T00:00:02Z","payload":{"type":"collection_gap","source":"APP_USAGE","from":"2026-09-04T00:00:00Z","to":"2026-09-05T00:00:00Z","reason":"HISTORY_LIMIT"}},
            {"id":"environment","observedAt":"2026-09-05T00:00:02Z","payload":{"type":"device_environment","networkTransports":["WIFI"],"internetValidated":true,"metered":false,"powerSaveMode":false,"deviceIdleMode":false,"timeZoneId":"UTC","bootCount":2,"elapsedRealtimeMillis":10000}}
        ]}"""
        fixture(repository, Contacts(), enrollment).use { http ->
            repeat(2) {
                val response = http.perform(eventRequest(body))
                assertEquals(200, response.status, response.contentAsString)
            }
            assertEquals(8, repository.events.size)
            assertTrue(repository.events.all { it.userId == worker.subjectUserId &&
                it.subject == ContextEvent.Subject.Device(worker.id) && it.source == ContextEvent.Source.Worker(worker.id) })
            assertEquals(time, repository.events.first().observedAt)
            assertTrue(repository.events.all { it.receivedAt > it.observedAt })
        }
    }

    @Test
    fun `authentication binding and bounded payload are required`() = runBlocking<Unit> {
        val repository = Events()
        val enrollment = Mockito.mock(WorkerEnrollmentRepository::class.java)
        Mockito.`when`(enrollment.authenticateGatewayCredential(hash())).thenReturn(worker("android"))
        fixture(repository, Contacts(), enrollment).use { http ->
            val insecure = post("/api/worker/events").secureTestRequest().secure(false).with { it.scheme = "http"; it }.content(eventJson)
            assertEquals(426, http.perform(insecure).status)
            assertEquals(401, http.perform(post("/api/worker/events").secureTestRequest().content(eventJson)).status)
            assertEquals(413, http.perform(eventRequest("x".repeat(256 * 1024 + 1))).status)
            assertEquals(400, http.perform(eventRequest(eventJson.replace("sample", "invalid/id"))).status)
            Mockito.`when`(enrollment.authenticateGatewayCredential(hash())).thenReturn(worker("macos").copy(subjectUserId = null))
            assertEquals(403, http.perform(eventRequest(eventJson)).status)
            assertTrue(repository.events.isEmpty())
        }
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
