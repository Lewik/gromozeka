package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.slot.*
import kotlinx.coroutines.*
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class SlotApplicationServiceTest {
    @Test fun `request grants asynchronously and produces one durable runtime continuation`() = fixture { f ->
        val slot = f.register()
        val request = f.acquire(slot)
        assertEquals(SlotRequest.State.PENDING, request.state)
        assertTrue(f.repository.snapshot(slot.number)!!.leases.isEmpty())
        assertTrue(f.coordinator.schedulingSnapshot(f.conversation.id).pendingTasks.isEmpty())
        f.service.processPending(); f.service.deliverPending(); f.service.deliverPending()
        assertEquals(SlotRequest.State.GRANTED, f.repository.findRequest(request.id)!!.state)
        assertEquals(1, f.coordinator.schedulingSnapshot(f.conversation.id).pendingTasks.size)
    }

    @Test fun `history changes cannot move lease but fork gets none`() = fixture { f ->
        val slot = f.register(); f.acquire(slot); f.service.processPending()
        val origin = assertNotNull(f.service.origin(f.user.id, f.conversation.id, slot.mountId))
        Mockito.`when`(f.conversations.findById(f.conversation.id)).thenReturn(f.conversation.copy(currentThread = Conversation.Thread.Id("compacted")))
        assertEquals(origin, f.service.origin(f.user.id, f.conversation.id, slot.mountId))
        Mockito.`when`(f.conversations.findById(f.conversation.id)).thenReturn(f.conversation.copy(currentThread = Conversation.Thread.Id("empty-history")))
        assertEquals(origin, f.service.origin(f.user.id, f.conversation.id, slot.mountId))
        assertNull(f.service.origin(f.user.id, Conversation.Id("fork"), slot.mountId))
        assertNull(f.service.origin(User.Id("other"), f.conversation.id, slot.mountId))
    }

    @Test fun `one conversation launches from several slots without a current slot switch`() = fixture { f ->
        val first = f.register(); val second = f.register("other")
        f.acquire(first); f.acquire(second); f.service.processPending()
        val a = assertNotNull(f.service.origin(f.user.id, f.conversation.id, first.mountId))
        val b = assertNotNull(f.service.origin(f.user.id, f.conversation.id, second.mountId))
        assertEquals(first.number.toString(), a.commandEnvironment()["GRZ_SLOT"])
        assertEquals(second.number.toString(), b.commandEnvironment()["GRZ_SLOT"])
        assertNotEquals(a.leaseId, b.leaseId)
        assertNull(f.service.origin(f.user.id, f.conversation.id, WorkspaceMount.Id("unregistered")))
    }

    @Test fun `foreign user and cross project cannot acquire or return lease`() = fixture { f ->
        val slot = f.register(); f.acquire(slot); f.service.processPending()
        val lease = f.repository.snapshot(slot.number)!!.leases.single()
        assertFails { f.service.acquire(f.user.copy(id = User.Id("other")), f.conversation.id, f.agent, slot.number, SlotAccess.WRITE, "foreign") }
        val other = f.conversation.copy(id = Conversation.Id("other-project"), projectId = Project.Id("other"))
        Mockito.`when`(f.conversations.findById(other.id)).thenReturn(other)
        assertFails { f.service.acquire(f.user, other.id, f.agent, slot.number, SlotAccess.READ, "cross-project") }
        val fork = f.conversation.copy(id = Conversation.Id("fork"))
        Mockito.`when`(f.conversations.findById(fork.id)).thenReturn(fork)
        assertFails { f.service.release(f.user, fork.id, slot.number, lease.id, null) }
        assertTrue(f.repository.findLease(lease.id)!!.active)
    }

    @Test fun `permission revocation rejects queued request without phantom grant`() = fixture { f ->
        val slot = f.register(); val request = f.acquire(slot)
        Mockito.`when`(f.workerAccess.requirePermission(f.user, f.worker.id, WorkerPermission.USE, f.project.id)).thenThrow(WorkerAccessDeniedException())
        f.service.processPending()
        assertEquals(SlotRequest.State.REJECTED, f.repository.findRequest(request.id)!!.state)
        assertTrue(f.repository.snapshot(slot.number)!!.leases.isEmpty())
    }

    @Test fun `deleted conversation keeps lease for explicit human reclaim`() = fixture { f ->
        val slot = f.register(); f.acquire(slot); f.service.processPending()
        val lease = f.repository.snapshot(slot.number)!!.leases.single()
        Mockito.`when`(f.conversations.findById(f.conversation.id)).thenReturn(null)
        assertTrue(f.repository.findLease(lease.id)!!.active)
        val prepared = f.service.prepareReclaim(f.user, lease.id)
        assertTrue(prepared.active)
        val reclaimed = f.service.confirmReclaim(f.user, lease.id, requireNotNull(prepared.reclaimConfirmation).id)
        assertFalse(reclaimed.active)
    }

    @Test fun `release confirmation skips new observations and old receipt cannot free successor`() = fixture { f ->
        val slot = f.register(); f.acquire(slot); f.service.processPending()
        val first = f.repository.snapshot(slot.number)!!.leases.single()
        val warning = f.service.release(f.user, f.conversation.id, slot.number, first.id, null)
        assertFalse(warning.released); assertEquals(1, f.inspections)
        assertTrue(f.service.release(f.user, f.conversation.id, slot.number, first.id, warning.confirmationId).released)
        assertEquals(1, f.inspections)
        f.acquire(slot, "second"); f.service.processPending()
        val next = f.repository.snapshot(slot.number)!!.leases.single()
        assertNotEquals(first.id, next.id)
        assertFails { f.service.release(f.user, f.conversation.id, slot.number, next.id, warning.confirmationId) }
        assertTrue(f.service.release(f.user, f.conversation.id, slot.number, first.id, warning.confirmationId).released)
        assertTrue(f.repository.findLease(next.id)!!.active)
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val f = Fixture()
        try { f.setup(); block(f) } finally { f.scope.cancel() }
    }
    private class Fixture {
        val now = Instant.parse("2026-10-09T10:00:00Z")
        val user = User(User.Id("user"), displayName = "User", status = User.Status.ACTIVE, createdAt = now, updatedAt = now)
        val project = Project(Project.Id("project"), "Project", createdAt = now, lastUsedAt = now)
        val agent = AgentDefinition.Id("agent")
        val conversation = Conversation(Conversation.Id("conversation"), project.id,
            setOf(Conversation.Participant.User(user.id), Conversation.Participant.Agent(agent)), currentThread = Conversation.Thread.Id("thread"), createdAt = now, updatedAt = now)
        val worker = WorkerResource(ConversationRuntimeWorkerId("worker"), "Worker", user.id, runtimeWideAccess = true, status = WorkerResource.Status.ACTIVE, createdAt = now, updatedAt = now)
        val repository = InMemorySlotRepository()
        val conversations = Mockito.mock(ConversationDomainService::class.java)
        val projects = Mockito.mock(ProjectAccessService::class.java)
        val workspaces = Mockito.mock(WorkspaceDomainService::class.java)
        val workerAccess = Mockito.mock(WorkerAccessService::class.java)
        val users = Mockito.mock(UserDirectoryService::class.java)
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var inspections = 0
        val observations = object : SlotObservationProvider {
            override suspend fun inspect(slot: Slot, lease: SlotLease): SlotObservation {
                inspections++; return SlotObservation(now, listOf("Dirty checkout", "Pending command"))
            }
            override suspend fun activityWarnings(slot: Slot) = listOf("Known old command may still be running")
        }
        val service = SlotApplicationService(repository, conversations, projects, workspaces, workerAccess, users, observations, coordinator, scope)
        suspend fun setup() {
            Mockito.`when`(users.findActiveById(user.id)).thenReturn(user)
            Mockito.`when`(conversations.findById(conversation.id)).thenReturn(conversation)
            Mockito.`when`(projects.can(user.id, project.id, ProjectPermission.READ)).thenReturn(true)
            Mockito.`when`(projects.can(user.id, project.id, ProjectPermission.WRITE)).thenReturn(true)
            Mockito.`when`(workerAccess.requirePermission(user, worker.id, WorkerPermission.USE, project.id)).thenReturn(worker)
            Mockito.`when`(workerAccess.requireProjectAccess(worker.id, project.id)).thenReturn(worker)
            Mockito.`when`(workerAccess.findAccessible(user, worker.id, project.id)).thenReturn(worker)
        }
        suspend fun register(suffix: String = "one"): Slot {
            val workspace = Workspace(Workspace.Id("workspace-$suffix"), project.id, "Workspace", kind = Workspace.Kind.FILESYSTEM, createdAt = now, updatedAt = now)
            val mount = WorkspaceMount(WorkspaceMount.Id("mount-$suffix"), workspace.id, worker.id.value, "/checkout-$suffix", now, now)
            Mockito.`when`(workspaces.resolveExecution(mount.id)).thenReturn(WorkspaceExecutionContext(project, workspace, mount))
            Mockito.`when`(workspaces.findMount(mount.id)).thenReturn(mount)
            return service.register(user, conversation.id, mount.id, "main")
        }
        suspend fun acquire(slot: Slot, key: String = "first") = service.acquire(user, conversation.id, agent, slot.number, SlotAccess.WRITE, "${slot.number}:$key")
    }
}
