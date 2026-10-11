package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.*
import com.gromozeka.domain.slot.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
                s.execute(requireNotNull(javaClass.classLoader.getResource("db/migration/postgres/V71__automatic_slot_requests.sql")).readText())
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
    @Test fun `migration retains legacy exact requests and indexes their original owner`() = database(migrateAutomatic = false) { repository, source ->
        val slot = repository.create(slot("legacy"))
        val legacy = request("legacy", slot.number)
        // This is the pre-V71 shape, without either new defaulted JSON field or actor_user_id.
        source.connection.use { c -> c.prepareStatement("INSERT INTO slot_requests(id,slot_number,conversation_id,idempotency_key,requested_at,state,record_json) VALUES (?,?,?,?,?::timestamptz,?,?::jsonb)").use {
            it.setString(1, legacy.id); it.setLong(2, slot.number); it.setString(3, legacy.conversationId.value)
            it.setString(4, legacy.idempotencyKey); it.setString(5, legacy.requestedAt.toString()); it.setString(6, legacy.state.name)
            it.setString(7, Json.encodeToString(legacy)); it.executeUpdate()
        } }
        migrate(source, "V71__automatic_slot_requests.sql")
        val restarted = PostgresSlotRepository(source)
        assertEquals(legacy, restarted.findRequest(legacy.id))
        assertEquals(listOf(USER), restarted.pendingUsers())
        assertEquals(listOf(legacy), restarted.pendingRequests(USER))
        restarted.lockedQueue(USER) { it.save(request("automatic")) }
        assertEquals(2, restarted.pendingRequests(USER).size)
        assertEquals(listOf(legacy), restarted.snapshot(slot.number)!!.requests)
        assertTrue(restarted.pendingRequests(User.Id("foreign")).isEmpty())
    }

    @Test fun `empty catalog serializes automatic replay keys across independent repository instances`() = database { repository, source ->
        val other = PostgresSlotRepository(source)
        val winners = coroutineScope {
            (1..12).map { n -> async(Dispatchers.IO) {
                (if (n % 2 == 0) repository else other).lockedQueue(USER) { tx ->
                    tx.requestByKey("same-key") ?: request("attempt-$n").copy(idempotencyKey = "same-key").also(tx::save)
                }
            } }.awaitAll()
        }
        assertEquals(1, winners.map { it.id }.distinct().size)
        assertEquals(listOf(winners.first()), other.pendingRequests(USER))
        assertEquals(listOf(USER), other.pendingUsers())
        assertTrue(other.pendingUsers(USER).isEmpty())
        assertTrue(other.list(USER).isEmpty())
        assertNull(winners.first().slotNumber)
    }

    @Test fun `automatic selection lease request and event commit or roll back together across slots`() = database { repository, source ->
        val first = repository.create(slot("one")); val second = repository.create(slot("two"))
        repository.lockedQueue(USER) { tx -> (1..12).forEach { tx.save(request("request-$it")) } }
        assertFailsWith<IllegalStateException> { repository.lockedQueue(USER) { tx ->
            val pending = tx.pendingRequests()
            grant(tx, pending[0], tx.slots().first()); grant(tx, pending[1], tx.slots().last())
            error("abort both choices")
        } }
        assertTrue(repository.snapshot(first.number)!!.leases.isEmpty())
        assertTrue(repository.snapshot(second.number)!!.leases.isEmpty())
        assertEquals(12, repository.pendingRequests(USER).size)
        assertTrue(repository.pendingEvents().isEmpty())
        val restarted = PostgresSlotRepository(source)
        val grants = coroutineScope {
            (1..12).map { n -> async(Dispatchers.IO) {
                (if (n % 2 == 0) repository else restarted).lockedQueue(USER) { tx ->
                    val candidate = tx.slots().sortedBy { it.slot.number }.firstOrNull { it.activeLeases().none { lease -> lease.access == SlotAccess.WRITE } }
                    val pending = tx.pendingRequests().firstOrNull()
                    if (candidate == null || pending == null) null else grant(tx, pending, candidate)
                }
            } }.awaitAll().filterNotNull()
        }
        assertEquals(setOf(first.number, second.number), grants.map { it.slotNumber }.toSet())
        assertEquals(2, grants.size)
        assertEquals(10, restarted.pendingRequests(USER).size)
        assertEquals(2, restarted.pendingEvents().size)
        grants.forEach { lease ->
            assertEquals(lease, restarted.findLease(lease.id))
            val saved = restarted.findRequest(lease.id.removePrefix("lease-"))!!
            assertNull(saved.slotNumber); assertEquals(lease.slotNumber, saved.grantedSlotNumber); assertEquals(lease.id, saved.leaseId)
        }
    }

    @Test fun `exact mutators and automatic queue selection cannot race a second writer`() = database { repository, source ->
        val slot = repository.create(slot("one"))
        val restarted = PostgresSlotRepository(source)
        val winners = coroutineScope {
            (1..12).map { n -> async(Dispatchers.IO) {
                fun take(tx: SlotTransaction): Boolean {
                    if (tx.activeLeases().any { it.access == SlotAccess.WRITE }) return false
                    tx.save(SlotLease("lease-$n", slot.number, Conversation.Id("conversation-$n"), USER, AGENT, SlotAccess.WRITE, NOW))
                    return true
                }
                if (n % 2 == 0) repository.lockedQueue(USER) { take(it.slots().single()) }
                else restarted.locked(slot.number) { take(it) }
            } }.awaitAll()
        }
        assertEquals(1, winners.count { it })
        assertEquals(1, repository.snapshot(slot.number)!!.leases.size)
    }

    @Test fun `cancellation and automatic grant have one atomic winner without releasing granted lease`() = database { repository, source ->
        val slot = repository.create(slot("one"))
        val restarted = PostgresSlotRepository(source)
        repeat(12) { n ->
            val request = request("race-$n")
            repository.lockedQueue(USER) { it.save(request) }
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val cancel = async(Dispatchers.IO) {
                    start.await()
                    restarted.lockedQueue(USER) { tx ->
                        val current = tx.request(request.id)!!
                        if (current.state == SlotRequest.State.PENDING) tx.save(current.copy(state = SlotRequest.State.CANCELLED))
                    }
                }
                val acquire = async(Dispatchers.IO) {
                    start.await()
                    repository.lockedQueue(USER) { tx ->
                        val current = tx.request(request.id)!!
                        if (current.state == SlotRequest.State.PENDING) grant(tx, current, tx.slots().single())
                        Unit
                    }
                }
                start.complete(Unit); cancel.await(); acquire.await()
            }
            val saved = restarted.findRequest(request.id)!!
            val leases = restarted.snapshot(slot.number)!!.leases
            when (saved.state) {
                SlotRequest.State.CANCELLED -> { assertTrue(leases.isEmpty()); assertNull(saved.leaseId) }
                SlotRequest.State.GRANTED -> {
                    assertEquals(saved.leaseId, leases.single().id); assertEquals(slot.number, saved.grantedSlotNumber)
                    restarted.locked(slot.number) { tx -> tx.save(leases.single().copy(releasedAt = NOW)) }
                }
                else -> fail("Race left request unresolved: ${saved.state}")
            }
        }
    }

    // Repository-level atomicity tests deliberately avoid depending on :application's selection policy.
    private fun grant(tx: SlotQueueTransaction, request: SlotRequest, selected: SlotTransaction): SlotLease {
        val lease = SlotLease("lease-${request.id}", selected.slot.number, request.conversationId, USER, AGENT, request.access, NOW)
        selected.save(lease)
        tx.save(request.copy(state = SlotRequest.State.GRANTED, leaseId = lease.id, grantedSlotNumber = lease.slotNumber))
        tx.emit(SlotEvent("event-${request.id}", lease.slotNumber, request.id, lease.id, request.conversationId, USER, AGENT, SlotEvent.Kind.GRANTED, NOW))
        return lease
    }
    private fun slot(id: String) = Slot(id, 0, USER, PROJECT, Workspace.Id("workspace-$id"), WorkspaceMount.Id("mount-$id"), createdAt = NOW)
    private fun request(id: String, number: Long? = null) = SlotRequest(id, "key-$id", number, Conversation.Id("conversation-$id"), USER, AGENT, SlotAccess.WRITE, NOW,
        projectId = if (number == null) PROJECT else null)
    private fun migrate(source: PGSimpleDataSource, resource: String) {
        source.connection.use { c -> c.createStatement().use { it.execute(requireNotNull(javaClass.classLoader.getResource("db/migration/postgres/$resource")).readText()) } }
    }
    private fun database(migrateAutomatic: Boolean = true, block: suspend (PostgresSlotRepository, PGSimpleDataSource) -> Unit) = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking
        fun source() = PGSimpleDataSource().apply {
            setURL(System.getenv("GROMOZEKA_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/gromozeka")
            user = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
            password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
        }
        val schema = "slots_test_${UUID.randomUUID().toString().replace("-", "")}"; val admin = source()
        admin.connection.use { it.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            val isolated = source().apply { currentSchema = schema }
            isolated.connection.use { it.createStatement().use { it.execute("CREATE TABLE worker_requests (completed_at TIMESTAMPTZ)") } }
            migrate(isolated, "V68__development_slots.sql")
            if (migrateAutomatic) migrate(isolated, "V71__automatic_slot_requests.sql")
            block(PostgresSlotRepository(isolated), isolated)
        } finally {
            admin.connection.use { it.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
    private companion object {
        val NOW = Instant.parse("2026-10-11T00:00:00Z")
        val USER = User.Id("alice")
        val PROJECT = Project.Id("project")
        val AGENT = AgentDefinition.Id("agent")
    }

}
