package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.*
import com.gromozeka.domain.slot.*
import kotlinx.coroutines.*
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import kotlin.test.*
import kotlin.time.Instant

class PostgresSlotRepositoryTest {
    @Test fun `slot transactions survive restart rollback together and serialize competing writers`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking
        val schema = "slots_test_${UUID.randomUUID().toString().replace("-", "")}"
        fun source() = PGSimpleDataSource().apply {
            setURL(System.getenv("GROMOZEKA_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/gromozeka")
            user = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
            password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
        }
        val admin = source()
        admin.connection.use { it.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            val dataSource = source().apply { currentSchema = schema }
            dataSource.connection.use { c -> c.createStatement().use { s ->
                s.execute("CREATE TABLE worker_requests (completed_at TIMESTAMPTZ)")
                s.execute(requireNotNull(javaClass.classLoader.getResource("db/migration/postgres/V68__development_slots.sql")).readText())
            } }
            val repository = PostgresSlotRepository(dataSource)
            val now = Instant.parse("2026-10-09T10:00:00Z")
            val user = User.Id("alice")
            val slot = repository.create(Slot("slot", 0, user, Project.Id("project"), Workspace.Id("workspace"), WorkspaceMount.Id("mount"), createdAt = now))
            assertTrue(slot.number > 0)
            assertEquals(listOf(slot), repository.list(user))
            assertTrue(repository.list(User.Id("bob")).isEmpty())
            assertFails { repository.create(slot.copy(id = "duplicate")) }
            val request = SlotRequest("request", "request-key", slot.number, Conversation.Id("conversation"), user, AgentDefinition.Id("agent"), SlotAccess.WRITE, now)
            repository.locked(slot.number) { it.save(request) }
            val lease = SlotLease("lease", slot.number, request.conversationId, user, request.agentId, SlotAccess.WRITE, now)
            val event = SlotEvent("event", slot.number, request.id, lease.id, request.conversationId, user, request.agentId, SlotEvent.Kind.GRANTED, now)
            assertFailsWith<IllegalStateException> { repository.locked(slot.number) { tx ->
                tx.save(lease); tx.save(request.copy(state = SlotRequest.State.GRANTED, leaseId = lease.id)); tx.emit(event)
                error("abort transaction")
            } }
            assertNull(repository.findLease(lease.id))
            assertEquals(SlotRequest.State.PENDING, repository.findRequest(request.id)!!.state)
            assertTrue(repository.pendingEvents().isEmpty())
            repository.locked(slot.number) { tx ->
                tx.save(lease); tx.save(request.copy(state = SlotRequest.State.GRANTED, leaseId = lease.id)); tx.emit(event)
            }
            val restarted = PostgresSlotRepository(dataSource)
            assertEquals(lease, restarted.findLease(lease.id))
            assertEquals(event.id, restarted.pendingEvents().single().id)
            assertEquals(listOf(lease), restarted.snapshot(slot.number)!!.leases)
            assertFails { restarted.locked(slot.number) { it.save(lease.copy(id = "illegal-second-writer", conversationId = Conversation.Id("other"))) } }
            val reader = lease.copy(id = "reader", access = SlotAccess.READ, conversationId = Conversation.Id("reader"))
            restarted.locked(slot.number) { it.save(reader) }
            assertEquals(2, restarted.snapshot(slot.number)!!.leases.size)
            restarted.locked(slot.number) { it.save(lease.copy(releasedAt = now)) }
            val winners = coroutineScope {
                (1..12).map { n -> async(Dispatchers.IO) {
                    restarted.locked(slot.number) { tx ->
                        if (tx.activeLeases().any { it.access == SlotAccess.WRITE }) false
                        else { tx.save(lease.copy(id = "writer-$n", conversationId = Conversation.Id("writer-$n"))); true }
                    }
                } }.awaitAll()
            }
            assertEquals(1, winners.count { it })
            assertEquals(1, restarted.snapshot(slot.number)!!.leases.count { it.access == SlotAccess.WRITE })
            val after = restarted.pendingEvents().single().sequence
            assertTrue(restarted.pendingEvents(after).isEmpty())
            restarted.settleEvent(event.id)
            assertTrue(restarted.pendingEvents().isEmpty())
            // No FK to an internal thread or cascading Conversation lifetime exists.
            assertEquals(lease.copy(releasedAt = now), restarted.findLease(lease.id))
            restarted.locked(slot.number) { tx -> tx.activeLeases().forEach { tx.save(it.copy(releasedAt = now)) }; tx.save(slot.copy(retired = true)) }
            val replacement = restarted.create(slot.copy(id = "replacement"))
            assertTrue(replacement.number > slot.number)
            assertEquals(listOf(replacement), restarted.list(user))
        } finally {
            admin.connection.use { it.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
}
