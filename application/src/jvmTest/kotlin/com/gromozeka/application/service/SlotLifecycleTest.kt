package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.slot.*
import kotlin.test.*
import kotlin.time.*
import kotlin.time.Duration.Companion.minutes

class SlotLifecycleTest {
    private val clock = object : Clock {
        var time = Instant.parse("2026-10-09T10:00:00Z")
        override fun now() = time
    }
    private val lifecycle = SlotLifecycle(clock)
    private val user = User.Id("user")
    private val agent = AgentDefinition.Id("agent")
    private fun session(number: Long = 1) = Session(Slot("slot-$number", number, user, Project.Id("project"),
        Workspace.Id("workspace-$number"), WorkspaceMount.Id("mount-$number"), createdAt = clock.now()))
    private fun request(id: String, conversation: String = id, access: SlotAccess = SlotAccess.WRITE, number: Long = 1) =
        SlotRequest(id, "key-$id", number, Conversation.Id(conversation), user, agent, access, clock.now())
    private fun grant(tx: Session, request: SlotRequest): SlotLease {
        lifecycle.enqueue(tx, request)
        return lifecycle.grant(tx, mapOf(request.id to null)).single()
    }

    @Test fun `free slot still needs request processing and grant writes event`() {
        val tx = session(); val r = request("a")
        assertEquals(SlotRequest.State.PENDING, lifecycle.enqueue(tx, r).state)
        assertTrue(tx.activeLeases().isEmpty()); assertTrue(tx.events.isEmpty())
        val lease = lifecycle.grant(tx, mapOf(r.id to null)).single()
        assertEquals(clock.now(), lease.acquiredAt); assertNull(lease.releasedAt)
        assertEquals(lease.id, tx.request(r.id)?.leaseId)
        assertEquals(SlotEvent.Kind.GRANTED, tx.events.single().kind)
        assertTrue(lifecycle.grant(tx, mapOf(r.id to null)).isEmpty())
        assertEquals(1, tx.events.size)
    }

    @Test fun `one writer coexists with readers but another writer queues`() {
        val tx = session()
        grant(tx, request("a"))
        grant(tx, request("b", access = SlotAccess.READ))
        lifecycle.enqueue(tx, request("c"))
        assertTrue(lifecycle.grant(tx, mapOf("c" to null)).isEmpty())
        assertEquals(2, tx.activeLeases().size)
        val writer = tx.activeLeases().single { it.access == SlotAccess.WRITE }
        lifecycle.release(tx, writer.id, null, SlotObservation(clock.now(), emptyList()))
        assertEquals("c", lifecycle.grant(tx, mapOf("c" to null)).single().conversationId.value)
    }

    @Test fun `one conversation can hold several independent slots`() {
        val first = session(); val second = session(2)
        val a = grant(first, request("a", conversation = "same"))
        val b = grant(second, request("b", conversation = "same", number = 2))
        assertEquals(a.conversationId, b.conversationId); assertNotEquals(a.id, b.id)
    }

    @Test fun `same request retry is idempotent but changed arguments fail`() {
        val tx = session(); val r = request("a")
        lifecycle.enqueue(tx, r)
        assertEquals(r, lifecycle.enqueue(tx, r.copy(id = "retry")))
        assertFailsWith<IllegalArgumentException> { lifecycle.enqueue(tx, r.copy(access = SlotAccess.READ)) }
        assertEquals(1, tx.pendingRequests().size)
    }

    @Test fun `duplicate conversation request is not a second reservation`() {
        val tx = session(); grant(tx, request("a", conversation = "same"))
        assertFailsWith<IllegalArgumentException> { lifecycle.enqueue(tx, request("b", conversation = "same")) }
    }

    @Test fun `soft return keeps lease until issued confirmation is echoed`() {
        val tx = session(); val lease = grant(tx, request("a"))
        val observation = SlotObservation(clock.now(), listOf("Dirty checkout", "Command still running"))
        val warned = lifecycle.release(tx, lease.id, null, observation)
        assertFalse(warned.released); assertTrue(tx.lease(lease.id)!!.active)
        assertNull(tx.lease(lease.id)!!.releasedAt)
        assertFailsWith<IllegalArgumentException> { lifecycle.release(tx, lease.id, "force", null) }
        clock.time += 1.minutes
        // No second observation: arbitrary disk/process changes cannot create an unwaivable cleanup gate.
        val released = lifecycle.release(tx, lease.id, warned.confirmationId, null)
        assertTrue(released.released); assertEquals(clock.now(), tx.lease(lease.id)!!.releasedAt)
        clock.time += 20.minutes
        assertEquals(released, lifecycle.release(tx, lease.id, warned.confirmationId, null))
    }

    @Test fun `old confirmation cannot release next occupation`() {
        val tx = session(); val first = grant(tx, request("a"))
        val warning = lifecycle.release(tx, first.id, null, SlotObservation(clock.now(), listOf("Unknown")))
        lifecycle.release(tx, first.id, warning.confirmationId, null)
        val next = grant(tx, request("b"))
        assertFailsWith<IllegalArgumentException> { lifecycle.release(tx, next.id, warning.confirmationId, null) }
        assertTrue(lifecycle.release(tx, first.id, warning.confirmationId, null).released)
        assertTrue(tx.lease(next.id)!!.active)
    }

    @Test fun `expiry never releases lease and renewed warning gets another token`() {
        val tx = session(); val lease = grant(tx, request("a"))
        val observation = SlotObservation(clock.now(), listOf("Unavailable Worker"))
        val first = lifecycle.release(tx, lease.id, null, observation)
        clock.time += 16.minutes
        assertFailsWith<IllegalArgumentException> { lifecycle.release(tx, lease.id, first.confirmationId, null) }
        assertTrue(tx.lease(lease.id)!!.active)
        val next = lifecycle.release(tx, lease.id, null, observation.copy(observedAt = clock.now()))
        assertNotEquals(first.confirmationId, next.confirmationId)
    }

    @Test fun `cancellation before grant removes request without creating lease`() {
        val tx = session(); lifecycle.enqueue(tx, request("a"))
        assertEquals(SlotRequest.State.CANCELLED, lifecycle.cancel(tx, "a").state)
        assertTrue(lifecycle.grant(tx, mapOf("a" to null)).isEmpty())
        assertTrue(tx.activeLeases().isEmpty())
    }

    @Test fun `cancellation after grant does not silently release occupation`() {
        val tx = session(); val lease = grant(tx, request("a"))
        assertEquals(SlotRequest.State.GRANTED, lifecycle.cancel(tx, "a").state)
        assertTrue(tx.lease(lease.id)!!.active)
    }

    @Test fun `unvalidated new arrivals wait and ineligible head does not starve next request`() {
        val tx = session()
        lifecycle.enqueue(tx, request("a")); lifecycle.enqueue(tx, request("b"))
        assertTrue(lifecycle.grant(tx, emptyMap()).isEmpty())
        val granted = lifecycle.grant(tx, mapOf("a" to "User removed", "b" to null))
        assertEquals("b", granted.single().conversationId.value)
        assertEquals(SlotRequest.State.REJECTED, tx.request("a")!!.state)
    }

    @Test fun `reclaim preparation is not authorization to release`() {
        val tx = session(); val lease = grant(tx, request("a"))
        val prepared = lifecycle.prepareReclaim(tx, lease.id)
        assertTrue(prepared.active)
        assertFailsWith<IllegalArgumentException> { lifecycle.confirmReclaim(tx, lease.id, "user_confirmed") }
        val result = lifecycle.confirmReclaim(tx, lease.id, prepared.reclaimConfirmation!!.id)
        assertFalse(result.active)
        assertEquals(result, lifecycle.confirmReclaim(tx, lease.id, prepared.reclaimConfirmation!!.id))
        assertEquals(1, tx.events.count { it.kind == SlotEvent.Kind.RECLAIMED })
    }

    @Test fun `retired and foreign-owned slots reject acquisition`() {
        val tx = session(); tx.slot = tx.slot.copy(retired = true)
        assertFailsWith<IllegalArgumentException> { lifecycle.enqueue(tx, request("a")) }
        tx.slot = tx.slot.copy(retired = false)
        assertFailsWith<IllegalArgumentException> { lifecycle.enqueue(tx, request("a").copy(actorUserId = User.Id("other"))) }
    }

    private class Session(override var slot: Slot) : SlotTransaction {
        val leases = linkedMapOf<String, SlotLease>()
        val requests = linkedMapOf<String, SlotRequest>()
        val events = mutableListOf<SlotEvent>()
        override fun activeLeases() = leases.values.filter { it.active }
        override fun pendingRequests() = requests.values.filter { it.state == SlotRequest.State.PENDING }.sortedWith(compareBy({ it.requestedAt }, { it.id }))
        override fun lease(id: String) = leases[id]
        override fun request(id: String) = requests[id]
        override fun requestByKey(key: String) = requests.values.singleOrNull { it.idempotencyKey == key }
        override fun save(slot: Slot) { this.slot = slot }
        override fun save(lease: SlotLease) { leases[lease.id] = lease }
        override fun save(request: SlotRequest) { requests[request.id] = request }
        override fun emit(event: SlotEvent) { if (events.none { it.id == event.id }) events += event }
    }
}
