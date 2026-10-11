package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.slot.*
import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock
import kotlin.time.Instant

class SlotSelectionLifecycleTest {
    @Test fun `automatic choice is unbound until grant and selects lowest eligible free number`() = fixture { f ->
        val first = f.slot(); val second = f.slot()
        val request = f.request("a")
        assertNull(f.enqueue(request).slotNumber)
        assertTrue(f.repository.pendingEvents().isEmpty())
        val granted = f.grant(mapOf(request.id to SlotRequestEligibility(linkedSetOf(second.number, first.number)))).single()
        assertEquals(first.number, granted.slotNumber)
        val saved = f.repository.findRequest(request.id)!!
        assertNull(saved.slotNumber) // Immutable selector remains automatic for idempotency.
        assertEquals(first.number, saved.grantedSlotNumber)
        assertEquals(granted.id, saved.leaseId)
        assertEquals(first.number, f.repository.pendingEvents().single().slotNumber)
        assertEquals(saved, f.enqueue(request.copy(id = "retry")))
        assertTrue(f.grant().isEmpty())
    }

    @Test fun `automatic writer skips occupied writer but exact number never falls back`() = fixture { f ->
        val first = f.slot(); val second = f.slot()
        f.occupy(first)
        f.enqueue(f.request("a", number = first.number))
        f.enqueue(f.request("b"))
        assertEquals(second.number, f.grant().single().slotNumber)
        assertEquals(SlotRequest.State.PENDING, f.repository.findRequest("a")!!.state)
    }

    @Test fun `held slots are not reused or upgraded and fresh requests can hold several slots`() = fixture { f ->
        val first = f.slot(); val second = f.slot(); val third = f.slot()
        val read = f.occupy(first, conversation = "same", access = SlotAccess.READ)
        f.enqueue(f.request("a", conversation = "same"))
        f.enqueue(f.request("b", conversation = "same"))
        assertEquals(listOf(second.number, third.number), f.grant().map { it.slotNumber })
        assertEquals(read, f.repository.findLease(read.id))
        f.enqueue(f.request("c", conversation = "same", access = SlotAccess.READ))
        assertTrue(f.grant().isEmpty())
        assertEquals(SlotRequest.State.PENDING, f.repository.findRequest("c")!!.state)
        assertFailsWith<IllegalArgumentException> { f.enqueue(f.request("exact", conversation = "same", number = first.number)) }
    }

    @Test fun `automatic request does not consume same conversation pending exact target`() = fixture { f ->
        val first = f.slot(); val second = f.slot()
        f.enqueue(f.request("a", conversation = "same"))
        f.enqueue(f.request("b", conversation = "same", number = first.number))
        val granted = f.grant()
        assertEquals(listOf(second.number, first.number), granted.map { it.slotNumber })
        assertEquals(2, granted.map { it.id }.distinct().size)
    }

    @Test fun `automatic read is shared with writer and other readers not a snapshot`() = fixture { f ->
        val slot = f.slot(); val writer = f.occupy(slot)
        f.enqueue(f.request("a", access = SlotAccess.READ))
        f.enqueue(f.request("b", access = SlotAccess.READ))
        assertEquals(listOf(slot.number, slot.number), f.grant().map { it.slotNumber })
        assertEquals(3, f.repository.snapshot(slot.number)!!.leases.size)
        assertTrue(f.repository.findLease(writer.id)!!.active)
    }

    @Test fun `older waiting automatic writer fences all eligible slots against younger readers`() = fixture { f ->
        val first = f.slot(); val second = f.slot()
        val writer = f.occupy(first); f.occupy(second)
        f.enqueue(f.request("a"))
        f.enqueue(f.request("b", number = first.number, access = SlotAccess.READ))
        f.enqueue(f.request("c", access = SlotAccess.READ))
        assertTrue(f.grant().isEmpty())
        f.release(writer)
        val granted = f.grant()
        assertEquals(listOf("a", "b", "c"), granted.map { it.conversationId.value })
        assertEquals(listOf(first.number, first.number, first.number), granted.map { it.slotNumber })
    }

    @Test fun `blocked exact writer preserves per slot FIFO but does not block unrelated slots`() = fixture { f ->
        val first = f.slot(); val second = f.slot()
        f.occupy(first)
        f.enqueue(f.request("a", number = first.number))
        f.enqueue(f.request("b", number = first.number, access = SlotAccess.READ))
        f.enqueue(f.request("c", number = second.number))
        assertEquals("c", f.grant().single().conversationId.value)
        assertEquals(listOf("a", "b"), f.repository.pendingRequests(f.user).map { it.id })
    }

    @Test fun `FIFO uses request timestamp before id and id breaks equal timestamp ties`() = fixture { f ->
        f.slot()
        f.enqueue(f.request("z").copy(requestedAt = f.now - 1.seconds))
        f.enqueue(f.request("b")); f.enqueue(f.request("a"))
        val oldest = f.grant().single(); assertEquals("z", oldest.conversationId.value)
        f.release(oldest)
        assertEquals("a", f.grant().single().conversationId.value)
    }

    @Test fun `noneligible and other project candidates do not create head of line blocking`() = fixture { f ->
        val first = f.slot(); val other = f.slot(Project.Id("other-project"))
        f.occupy(first)
        f.enqueue(f.request("a"))
        f.enqueue(f.request("b", project = other.projectId))
        val granted = f.grant(mapOf("a" to SlotRequestEligibility(setOf(first.number)), "b" to SlotRequestEligibility(setOf(other.number))))
        assertEquals(other.number, granted.single().slotNumber)
    }

    @Test fun `empty catalog or empty eligibility stays pending and later additions can satisfy it`() = fixture { f ->
        f.enqueue(f.request("a"))
        assertTrue(f.grant().isEmpty()); assertTrue(f.repository.pendingEvents().isEmpty())
        val slot = f.slot()
        assertTrue(f.grant(mapOf("a" to SlotRequestEligibility())).isEmpty())
        assertEquals(slot.number, f.grant().single().slotNumber)
    }

    @Test fun `retirement between validation and transaction cannot grant stale candidate`() = fixture { f ->
        val first = f.slot(); val second = f.slot()
        f.enqueue(f.request("a"))
        f.repository.locked(first.number) { it.save(first.copy(retired = true)) }
        assertEquals(second.number, f.grant(mapOf("a" to SlotRequestEligibility(setOf(first.number, second.number)))).single().slotNumber)
    }

    @Test fun `revoked requester rejects unbound request without slot and unblocks next request`() = fixture { f ->
        val slot = f.slot()
        f.enqueue(f.request("a")); f.enqueue(f.request("b"))
        assertEquals("b", f.grant(mapOf("a" to SlotRequestEligibility(rejection = "Access revoked"), "b" to SlotRequestEligibility(setOf(slot.number)))).single().conversationId.value)
        val rejected = f.repository.pendingEvents().single { it.kind == SlotEvent.Kind.REQUEST_REJECTED }
        assertNull(rejected.slotNumber); assertNull(rejected.leaseId)
    }

    @Test fun `unvalidated arrival is not selected using another request eligibility`() = fixture { f ->
        f.slot(); f.slot()
        f.enqueue(f.request("a")); f.enqueue(f.request("b"))
        assertTrue(f.grant(mapOf("b" to SlotRequestEligibility(setOf(1L, 2L)))).isEmpty())
        assertEquals(2, f.grant().size)
    }

    @Test fun `automatic cancellation before or after grant is idempotent and cannot release a lease`() = fixture { f ->
        f.slot()
        f.enqueue(f.request("a")); assertEquals(SlotRequest.State.CANCELLED, f.cancel("a").state)
        assertEquals(SlotRequest.State.CANCELLED, f.cancel("a").state)
        assertTrue(f.grant().isEmpty())
        f.enqueue(f.request("b")); val lease = f.grant().single()
        val result = f.cancel("b")
        assertEquals(SlotRequest.State.GRANTED, result.state)
        assertEquals(lease.slotNumber, result.grantedSlotNumber)
        assertEquals(result, f.cancel("b")); assertTrue(f.repository.findLease(lease.id)!!.active)
    }

    @Test fun `idempotency key cannot change automatic selector project owner conversation agent or mode`() = fixture { f ->
        val slot = f.slot(); val original = f.request("a"); f.enqueue(original)
        listOf(
            original.copy(slotNumber = slot.number, projectId = null), original.copy(projectId = Project.Id("other")),
            original.copy(actorUserId = User.Id("other")), original.copy(conversationId = Conversation.Id("other")),
            original.copy(agentId = AgentDefinition.Id("other")), original.copy(access = SlotAccess.READ),
        ).forEach { changed -> assertFailsWith<IllegalArgumentException> { f.enqueue(changed.copy(id = "retry")) } }
        assertEquals(listOf(original), f.repository.pendingRequests(f.user))
    }

    @Test fun `queue rollback leaves no partial selected lease or event`() = fixture { f ->
        val slot = f.slot(); f.enqueue(f.request("a"))
        assertFailsWith<IllegalStateException> { f.repository.lockedQueue(f.user) { tx ->
            f.lifecycle.grant(tx, mapOf("a" to SlotRequestEligibility(setOf(slot.number))))
            error("abort")
        } }
        assertTrue(f.repository.snapshot(slot.number)!!.leases.isEmpty())
        assertEquals(SlotRequest.State.PENDING, f.repository.findRequest("a")!!.state)
        assertTrue(f.repository.pendingEvents().isEmpty())
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking { block(Fixture()) }
    private class Fixture {
        val now = Instant.parse("2026-10-11T00:00:00Z")
        val user = User.Id("user")
        val project = Project.Id("project")
        val agent = AgentDefinition.Id("agent")
        val repository = InMemorySlotRepository()
        val lifecycle = SlotLifecycle(object : Clock { override fun now() = now })
        private var sequence = 0
        suspend fun slot(projectId: Project.Id = project): Slot {
            val id = ++sequence
            return repository.create(Slot("slot-$id", 0, user, projectId, Workspace.Id("ws-$id"), WorkspaceMount.Id("mount-$id"), createdAt = now))
        }
        fun request(id: String, conversation: String = id, access: SlotAccess = SlotAccess.WRITE, number: Long? = null, project: Project.Id = this.project) =
            SlotRequest(id, "key-$id", number, Conversation.Id(conversation), user, agent, access, now, projectId = if (number == null) project else null)
        suspend fun enqueue(request: SlotRequest) = repository.lockedQueue(user) { lifecycle.enqueue(it, request) }
        suspend fun grant(eligibility: Map<String, SlotRequestEligibility>? = null) = repository.lockedQueue(user) { tx ->
            lifecycle.grant(tx, eligibility ?: tx.pendingRequests().associate { it.id to SlotRequestEligibility(tx.slots().map { candidate -> candidate.slot.number }.toSet()) })
        }
        suspend fun occupy(slot: Slot, conversation: String = "holder-${slot.number}", access: SlotAccess = SlotAccess.WRITE): SlotLease {
            val lease = SlotLease("held-${slot.number}-$conversation", slot.number, Conversation.Id(conversation), user, agent, access, now)
            repository.locked(slot.number) { it.save(lease) }
            return lease
        }
        suspend fun release(lease: SlotLease) = repository.locked(lease.slotNumber) { lifecycle.release(it, lease.id, null, SlotObservation(now, emptyList())) }
        suspend fun cancel(id: String) = repository.lockedQueue(user) { lifecycle.cancel(it, id) }
    }
}
