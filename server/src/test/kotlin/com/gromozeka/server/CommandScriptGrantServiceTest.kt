package com.gromozeka.server

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.BackgroundActivityOrigin
import com.gromozeka.domain.repository.BackgroundActivityOriginRepository
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.service.*
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class CommandScriptGrantServiceTest {
    @Test fun `one durable grant remains valid for days after its launch RPC disappears`() = runBlocking<Unit> {
        val f = fixture()
        f.service.register(f.task, f.bootstrap.tokenHash, f.now)
        val original = f.repository.rows.getValue(f.task.id)
        f.repository.launchPending = false
        val restarted = f.service()
        repeat(8) { day ->
            val caller = assertNotNull(restarted.authenticate(f.token, f.now + day.days))
            assertEquals(f.base.user.id, caller.user.id)
            assertEquals(f.base.agent.id, caller.agentDefinitionId)
            assertEquals(f.base.conversation.id, caller.conversationId)
        }
        assertEquals(1, f.repository.rows.size)
        assertEquals(original, f.repository.rows.getValue(f.task.id))
        assertFalse(original.toString().contains(f.token))
        assertFalse(f.bootstrap.toString().contains(f.token))
        assertFailsWith<IllegalArgumentException> { restarted.register(f.task, f.bootstrap.tokenHash, f.now + 8.days) }
    }

    @Test fun `cancellation during registration retires the grant before acknowledging startup`() = runBlocking<Unit> {
        val f = fixture()
        f.repository.afterRegister = { f.currentTask = f.task.copy(cancellationRequestedAt = f.now) }
        assertFailsWith<IllegalStateException> { f.service.register(f.task, f.bootstrap.tokenHash, f.now) }
        assertNotNull(f.repository.rows.getValue(f.task.id).revokedAt)
        assertNull(f.service.authenticate(f.token, f.now))
    }

    @Test fun `current user project Worker and Agent access is required on every call`() = runBlocking<Unit> {
        val f = fixture()
        f.service.register(f.task, f.bootstrap.tokenHash, f.now)
        assertNotNull(f.service.authenticate(f.token, f.now))
        Mockito.`when`(f.identities.findUserById(f.base.user.id)).thenReturn(f.base.user.copy(loginAllowed = false))
        assertNull(f.service.authenticate(f.token, f.now))
        Mockito.`when`(f.identities.findUserById(f.base.user.id)).thenReturn(f.base.user)
        Mockito.doThrow(ProjectAccessDeniedException()).`when`(f.base.projects)
            .requirePermission(f.base.user.id, f.base.project.id, ProjectPermission.WRITE)
        assertNull(f.service.authenticate(f.token, f.now))
        Mockito.reset(f.base.projects)
        Mockito.doThrow(WorkerAccessDeniedException()).`when`(f.base.workerAccess)
            .requirePermission(f.base.user, f.base.identity.workerId, WorkerPermission.USE, f.base.project.id)
        assertNull(f.service.authenticate(f.token, f.now))
        Mockito.reset(f.base.workerAccess)
        Mockito.`when`(f.base.agents.findById(f.base.agent.id)).thenReturn(null)
        assertNull(f.service.authenticate(f.token, f.now))
        Mockito.`when`(f.base.agents.findById(f.base.agent.id)).thenReturn(f.base.agent)
        assertNotNull(f.service.authenticate(f.token, f.now))
        Mockito.`when`(f.base.conversations.findById(f.base.conversation.id)).thenReturn(f.base.conversation.copy(
            participants = setOf(Conversation.Participant.User(User.Id("other-user")), Conversation.Participant.Agent(f.base.agent.id))))
        assertNull(f.service.authenticate(f.token, f.now))
    }

    @Test fun `terminal cancellation and command timeout revoke access without relying on heartbeat age`() = runBlocking<Unit> {
        for (kind in listOf("terminal", "cancel", "timeout")) {
            val f = fixture()
            f.service.register(f.task, f.bootstrap.tokenHash, f.now)
            val at = f.now + 7.days
            f.currentTask = when (kind) {
                "terminal" -> f.task.copy(status = CommandTask.Status.COMPLETED, completedAt = at)
                "cancel" -> f.task.copy(cancellationRequestedAt = at)
                else -> f.task.copy(timeoutAt = at)
            }
            assertNull(f.service.authenticate(f.token, at))
            assertNotNull(f.repository.rows.getValue(f.task.id).revokedAt)
            f.currentTask = f.task
            assertNull(f.service.authenticate(f.token, at), "A stale WORKING observation cannot revive a revoked token")
            f.service.maintain(at + 61.minutes)
            assertTrue(f.repository.rows.isEmpty())
            assertFailsWith<IllegalArgumentException> { f.service.register(f.task, f.bootstrap.tokenHash, at + 62.minutes) }
        }
    }

    @Test fun `daily reminders are idempotent across restart failed marker writes and missed days`() = runBlocking<Unit> {
        val f = fixture()
        f.service.register(f.task, f.bootstrap.tokenHash, f.now)
        f.service.maintain(f.now + 23.hours)
        assertTrue(f.notices.isEmpty())
        f.repository.failNextMarker = true
        assertFailsWith<IllegalStateException> { f.service.maintain(f.now + 1.days) }
        assertEquals(1, f.notices.size)
        f.service().maintain(f.now + 1.days + 1.minutes)
        assertEquals(1, f.notices.size)
        f.service().maintain(f.now + 2.days)
        f.service().maintain(f.now + 4.days)
        assertEquals(listOf(1, 2, 4), f.notices.keys.map { it.substringAfterLast(':').toInt() })
        assertEquals(4L, f.repository.rows.getValue(f.task.id).lastReminderDay)
        assertTrue(f.notices.values.all { it.conversationId == f.task.conversationId && !it.message.contains(f.token) })
        assertEquals(1, f.repository.rows.size, "An old active grant is not garbage")
        f.currentTask = f.task.copy(status = CommandTask.Status.COMPLETED, completedAt = f.now + 4.days)
        f.service.maintain(f.now + 5.days)
        assertEquals(3, f.notices.size)
    }

    @Test fun `missing command state authorizes nothing but is not treated as completed by GC`() = runBlocking<Unit> {
        val f = fixture()
        f.service.register(f.task, f.bootstrap.tokenHash, f.now)
        f.currentTask = null
        assertNull(f.service.authenticate(f.token, f.now + 3.days))
        f.service.maintain(f.now + 3.days)
        assertNull(f.repository.rows.getValue(f.task.id).revokedAt)
        assertContains(f.notices.values.single().message, "unavailable")
        f.currentTask = f.task
        assertNotNull(f.service.authenticate(f.token, f.now + 4.days))
    }

    private suspend fun fixture(): Fixture {
        val base = UnifiedMcpTestFixture(mcpTestProvider()).initialize()
        val f = Fixture(base)
        Mockito.`when`(base.workspaces.findMount(base.mount.id)).thenReturn(base.mount)
        Mockito.`when`(base.workspaces.findById(base.workspace.id)).thenReturn(base.workspace)
        Mockito.`when`(f.identities.findUserById(base.user.id)).thenReturn(base.user)
        Mockito.`when`(base.coordinator.findCommandTask(base.conversation.id, f.task.id)).thenAnswer { f.currentTask }
        Mockito.`when`(f.origins.find(base.conversation.id, setOf(f.origin.key))).thenReturn(mapOf(f.origin.key to f.origin))
        return f
    }

    private class Fixture(val base: UnifiedMcpTestFixture) {
        val now = base.now
        val bootstrap = CommandScriptBootstrap("https://example.test/mcp")
        val token = bootstrap.environment.getValue("GRZ_MCP_TOKEN")
        val task = CommandTask(CommandTask.Id("command"), base.conversation.id, base.identity.workerId, base.mount.id,
            agentDefinitionId = base.agent.id, command = "synthetic", workingDirectory = base.mount.rootPath,
            status = CommandTask.Status.WORKING, processId = 123, processStartedAt = now, processTreeId = 123,
            outputFile = "/not-opened", outputBytes = 0, createdAt = now, updatedAt = now,
            launchReference = BackgroundActivityLaunchReference("launch-request", "launch-call"))
        var currentTask: CommandTask? = task
        val origin = BackgroundActivityOrigin(BackgroundActivityOrigin.Kind.COMMAND, task.id.value, task.conversationId,
            task.agentDefinitionId, base.user.id, task.workerId, task.workspaceMountId, "launch-request", "launch-call")
        val repository = InMemoryGrants()
        val identities = Mockito.mock(IdentityRepository::class.java)
        val origins = Mockito.mock(BackgroundActivityOriginRepository::class.java)
        val notices = linkedMapOf<String, UserNotification>()
        private val publisher = UserNotificationPublisher { user, draft ->
            assertEquals(base.user.id, user)
            notices.getOrPut(requireNotNull(draft.sourceKey)) {
                UserNotification(UserNotification.Id("notice-${notices.size}"), user, draft.title, draft.message,
                    draft.level, now, conversationId = draft.conversationId)
            }
        }
        fun service() = CommandScriptGrantService(repository, origins, base.coordinator, base.conversations, identities,
            base.projects, base.workspaces, base.workerAccess, base.agents, publisher)
        val service = service()
    }

    private class InMemoryGrants : CommandScriptGrantRepository {
        val rows = linkedMapOf<CommandTask.Id, CommandScriptGrant>()
        private val issued = mutableSetOf<CommandTask.Id>()
        var launchPending = true
        var afterRegister: () -> Unit = {}
        var failNextMarker = false
        override suspend fun register(origin: BackgroundActivityOrigin, projectId: Project.Id, tokenHash: String, at: Instant): CommandScriptGrant {
            require(launchPending)
            val id = CommandTask.Id(origin.activityId)
            val existing = rows[id]
            val row = if (existing != null) {
                require(existing.origin == origin && existing.projectId == projectId && existing.tokenHash == tokenHash && existing.revokedAt == null)
                existing
            } else {
                require(issued.add(id)) { "Already retired" }
                CommandScriptGrant(origin, projectId, tokenHash, at, at, 0, null).also { rows[id] = it }
            }
            afterRegister()
            return row
        }
        override suspend fun findByHash(tokenHash: String): CommandScriptGrant? = rows.values.singleOrNull { it.tokenHash == tokenHash }
        override suspend fun revoke(conversationId: Conversation.Id, taskId: CommandTask.Id, at: Instant) {
            rows[taskId]?.takeIf { it.origin.conversationId == conversationId }?.let { rows[taskId] = it.copy(revokedAt = it.revokedAt ?: at) }
        }
        override suspend fun maintenanceCandidates(checkedBefore: Instant, limit: Int): List<CommandScriptGrant> =
            rows.values.filter { it.revokedAt == null && it.checkedAt <= checkedBefore }.sortedBy { it.checkedAt }.take(limit)
        override suspend fun markChecked(taskId: CommandTask.Id, at: Instant, reminderDay: Long) {
            if (failNextMarker) { failNextMarker = false; error("Synthetic marker failure") }
            rows[taskId]?.let { rows[taskId] = it.copy(checkedAt = at, lastReminderDay = maxOf(it.lastReminderDay, reminderDay)) }
        }
        override suspend fun deleteRevokedBefore(before: Instant, limit: Int): Int {
            val ids = rows.filterValues { it.revokedAt?.let { at -> at < before } == true }.keys.take(limit)
            ids.forEach(rows::remove)
            return ids.size
        }
    }
}
