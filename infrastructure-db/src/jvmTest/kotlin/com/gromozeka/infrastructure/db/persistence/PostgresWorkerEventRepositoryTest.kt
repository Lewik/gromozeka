package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.ContextEvent
import com.gromozeka.domain.model.ContextEventId
import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerAppState
import com.gromozeka.domain.model.WorkerContactKind
import com.gromozeka.domain.model.WorkerContactObservation
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.coroutines.runBlocking
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class PostgresWorkerEventRepositoryTest {
    @Test
    fun `migration replay conflict rollback and out of order projection are durable`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking
        val schema = "worker_events_test_${UUID.randomUUID().toString().replace("-", "")}"
        fun source() = PGSimpleDataSource().apply {
            setURL(System.getenv("GROMOZEKA_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/gromozeka")
            user = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
            password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
        }
        val admin = source()
        admin.connection.use { it.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            val db = source().apply { currentSchema = schema }
            db.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE users (id VARCHAR(255) PRIMARY KEY)")
                    statement.execute("CREATE TABLE workers (id VARCHAR(64) PRIMARY KEY)")
                    fun migration(file: String) {
                        requireNotNull(javaClass.classLoader.getResource("db/migration/postgres/$file")).readText()
                            .split(';').map(String::trim).filter(String::isNotEmpty).forEach(statement::execute)
                    }
                    migration("V37__context_state_and_mobile_workers.sql")
                    migration("V39__mobile_worker_contact_observations.sql")
                    statement.execute("INSERT INTO users VALUES ('subject')")
                    statement.execute("INSERT INTO workers(id, kind, subject_user_id) VALUES ('worker', 'MOBILE_DEVICE', 'subject')")
                    statement.execute("""
                        INSERT INTO context_state_events(id, user_id, source_kind, source_id, subject_kind, subject_id,
                            event_type, observed_at, received_at, source_json, payload_json)
                        VALUES ('old-event', 'subject', 'MOBILE_WORKER', 'worker', 'DEVICE', 'worker', 'BATTERY',
                            '2026-01-01', '2026-01-01', '{"type":"mobile_worker","workerId":"worker"}',
                            '{"type":"device","event":{"type":"battery","levelPercent":10,"charging":false}}')
                    """.trimIndent())
                    migration("V51__unify_worker_events.sql")
                }
            }
            val repository = PostgresContextStateRepository(db)
            val user = User.Id("subject")
            val worker = ConversationRuntimeWorkerId("worker")
            val subject = ContextEvent.Subject.Device(worker)
            assertEquals(ContextEvent.Source.Worker(worker), repository.history(user).single().source)
            val now = kotlin.time.Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
            val event = ContextEvent(ContextEventId("worker:worker:sample"), user, ContextEvent.Source.Worker(worker), subject,
                ContextEvent.Payload.Device(DeviceStateEvent.Battery(70, false)), now, now)
            assertEquals(setOf(event.id), repository.append(listOf(event)).acceptedEventIds)
            val restarted = PostgresContextStateRepository(db)
            assertEquals(setOf(event.id), restarted.append(listOf(event.copy(receivedAt = now + 1.seconds))).duplicateEventIds)
            val older = event.copy(id = ContextEventId("worker:worker:older"), observedAt = now - 60.seconds,
                payload = ContextEvent.Payload.Device(DeviceStateEvent.Battery(20, false)))
            restarted.append(listOf(older))
            assertEquals(event.payload, restarted.currentState(user, subject).single().payload)
            val rolledBack = event.copy(id = ContextEventId("rolled-back"))
            assertFailsWith<IllegalArgumentException> { restarted.append(listOf(rolledBack, event.copy(payload = older.payload))) }
            assertTrue(restarted.history(user).none { it.id == rolledBack.id })
            val firstLocation = event.copy(id = ContextEventId("worker:worker:location-one"),
                payload = ContextEvent.Payload.Device(DeviceStateEvent.Location(32.0, 34.8, 10.0, cause = com.gromozeka.domain.model.LocationCause.LIVE_TRACKING)), observedAt = now - 120.seconds)
            val lastLocation = event.copy(id = ContextEventId("worker:worker:location-two"),
                payload = ContextEvent.Payload.Device(DeviceStateEvent.Location(32.001, 34.8, 15.0, cause = com.gromozeka.domain.model.LocationCause.LIVE_TRACKING)), observedAt = now - 60.seconds)
            restarted.append(listOf(lastLocation, firstLocation))
            assertEquals(setOf(firstLocation.id), restarted.append(listOf(firstLocation)).duplicateEventIds)
            val locations = restarted.history(user, subject).filter { (it.payload as? ContextEvent.Payload.Device)?.event is DeviceStateEvent.Location }
            assertEquals(listOf(lastLocation, firstLocation), locations)
            val latest = restarted.currentState(user, subject).single { (it.payload as? ContextEvent.Payload.Device)?.event is DeviceStateEvent.Location }
            assertEquals(lastLocation.payload, latest.payload)
            assertEquals(lastLocation.observedAt, latest.observedAt)
            assertTrue(restarted.history(User.Id("another-user"), subject).isEmpty())
            PostgresWorkerContactRepository(db).record(WorkerContactObservation("contact", worker, user, WorkerContactKind.EVENT_BATCH,
                WorkerAppState.BACKGROUND, "test", now, now, 1, 1))
            db.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT last_request_id FROM worker_presence WHERE worker_id = 'worker'").use { rows ->
                        assertTrue(rows.next()); assertEquals("contact", rows.getString(1))
                    }
                }
            }
        } finally { admin.connection.use { it.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } } }
    }
}
