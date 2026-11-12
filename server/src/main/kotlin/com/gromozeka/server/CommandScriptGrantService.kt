package com.gromozeka.server

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.BackgroundActivityOrigin
import com.gromozeka.domain.repository.BackgroundActivityOriginRepository
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.service.*
import org.springframework.stereotype.Service
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal data class CommandScriptPrincipal(
    val user: User,
    val commandId: CommandTask.Id,
    val conversationId: Conversation.Id,
    val projectId: Project.Id,
    val agentDefinitionId: AgentDefinition.Id?,
)

@Service
internal class CommandScriptGrantService(
    private val grants: CommandScriptGrantRepository,
    private val origins: BackgroundActivityOriginRepository,
    private val coordinator: ConversationRuntimeCoordinator,
    private val conversations: ConversationRepository,
    private val identities: IdentityRepository,
    private val projects: ProjectAccessService,
    private val workspaces: WorkspaceDomainService,
    private val workers: WorkerAccessService,
    private val agents: AgentDomainService,
    private val notifications: UserNotificationPublisher,
) {
    suspend fun register(task: CommandTask, tokenHash: String, at: Instant = Clock.System.now()): CommandTask {
        CommandScriptTokens.requireValidHash(tokenHash)
        require(task.processId?.let { it > 0 } == true && task.processTreeId?.let { it > 0 } == true && task.processStartedAt != null) {
            "Command SDK registration requires a prepared native process"
        }
        val key = BackgroundActivityOrigin.Key(BackgroundActivityOrigin.Kind.COMMAND, task.id.value)
        val origin = requireNotNull(origins.find(task.conversationId, setOf(key))[key]) {
            "Command SDK registration has no trusted launch origin"
        }
        requireTaskIdentity(origin, task)
        require(isUsable(task, at)) { "Command cannot receive SDK access after cancellation or completion" }
        val conversation = requireNotNull(conversations.findById(task.conversationId)) { "Command conversation is unavailable" }
        requireNotNull(originUser(origin, conversation.projectId)) { "Command actor or scope is unavailable" }
        grants.register(origin, conversation.projectId, tokenHash, at)
        // A cancellation that raced with registration must be reflected in the ACK, never
        // replaced with the Worker's older WORKING snapshot.
        val current = coordinator.findCommandTask(task.conversationId, task.id)
        if (current == null || !isUsable(current, Clock.System.now())) {
            grants.revoke(task.conversationId, task.id, Clock.System.now())
            error("Command was cancelled or became unavailable during SDK registration")
        }
        requireTaskIdentity(origin, current)
        return current
    }

    suspend fun authenticate(token: String, at: Instant = Clock.System.now()): CommandScriptPrincipal? {
        if (!CommandScriptTokens.isCommandToken(token)) return null
        val grant = grants.findByHash(CommandScriptTokens.hash(token)) ?: return null
        if (grant.revokedAt != null) return null
        val taskId = CommandTask.Id(grant.origin.activityId)
        // Missing/stale observations are not proof of completion. They authorize no call,
        // but only an explicit terminal/cancel/deadline state can retire an active grant.
        val task = coordinator.findCommandTask(grant.origin.conversationId, taskId) ?: return null
        requireTaskIdentity(grant.origin, task)
        if (!isUsable(task, at)) {
            grants.revoke(task.conversationId, task.id, at)
            return null
        }
        val user = try {
            originUser(grant.origin, grant.projectId)
        } catch (_: ProjectAccessDeniedException) {
            return null
        } catch (_: WorkerAccessDeniedException) {
            return null
        } ?: return null
        return CommandScriptPrincipal(user, task.id, task.conversationId, grant.projectId, grant.origin.agentDefinitionId)
    }

    suspend fun observe(task: CommandTask, at: Instant = Clock.System.now()) {
        if (!isUsable(task, at)) grants.revoke(task.conversationId, task.id, at)
    }

    suspend fun maintain(at: Instant = Clock.System.now()) {
        for (grant in grants.maintenanceCandidates(at - 1.minutes, 128)) {
            val taskId = CommandTask.Id(grant.origin.activityId)
            val task = coordinator.findCommandTask(grant.origin.conversationId, taskId)
            task?.let { requireTaskIdentity(grant.origin, it) }
            if (task != null && !isUsable(task, at)) {
                grants.revoke(task.conversationId, task.id, at)
                continue
            }
            val day = if (at >= grant.createdAt) (at - grant.createdAt).inWholeDays else 0L
            var notifiedDay = grant.lastReminderDay
            if (day >= 1 && day > notifiedDay) {
                notifications.publish(grant.origin.actorUserId, UserNotificationDraft(
                    title = "Command SDK access is still retained",
                    message = "SDK authorization for command ${taskId.value} on Worker ${grant.origin.workerId.value} " +
                        "has been retained for $day day(s). " +
                        if (task == null) "The command's current state is unavailable; access is denied until its state is known."
                        else "Last known command status: ${task.status}. Cancel the command if this access is no longer needed.",
                    level = UserNotification.Level.WARNING,
                    conversationId = grant.origin.conversationId,
                    sourceKey = "command-sdk:${taskId.value}:day:$day",
                ))
                notifiedDay = day
            }
            // A stable per-day notification key also covers a crash after publication but
            // before this marker. Missed days do not produce a catch-up notification burst.
            grants.markChecked(taskId, at, notifiedDay)
        }
        grants.deleteRevokedBefore(at - 1.hours, 256)
    }

    private suspend fun originUser(origin: BackgroundActivityOrigin, projectId: Project.Id): User? {
        val user = identities.findUserById(origin.actorUserId)?.takeIf { it.canLogin } ?: return null
        val conversation = conversations.findById(origin.conversationId) ?: return null
        if (conversation.projectId != projectId || Conversation.Participant.User(user.id) !in conversation.participants) return null
        val mount = workspaces.findMount(origin.workspaceMountId) ?: return null
        val workspace = workspaces.findById(mount.workspaceId) ?: return null
        if (mount.workerId != origin.workerId.value || workspace.projectId != projectId) return null
        projects.requirePermission(user.id, projectId, ProjectPermission.WRITE)
        workers.requirePermission(user, origin.workerId, WorkerPermission.USE, projectId)
        workers.requireProjectAccess(origin.workerId, projectId)
        origin.agentDefinitionId?.let { id ->
            val agent = agents.findById(id) ?: return null
            if (Conversation.Participant.Agent(id) !in conversation.participants ||
                (agent.type !is AgentDefinition.Type.Global && agent.projectId != projectId)) return null
        }
        return user
    }

    private fun isUsable(task: CommandTask, at: Instant): Boolean =
        !task.isTerminal && task.cancellationRequestedAt == null && task.timeoutAt?.let { it <= at } != true

    private fun requireTaskIdentity(origin: BackgroundActivityOrigin, task: CommandTask) {
        check(origin.kind == BackgroundActivityOrigin.Kind.COMMAND && origin.activityId == task.id.value &&
            origin.conversationId == task.conversationId && origin.workerId == task.workerId &&
            origin.workspaceMountId == task.workspaceMountId && origin.agentDefinitionId == task.agentDefinitionId &&
            task.launchReference?.workerRequestId == origin.workerRequestId && task.launchReference?.toolCallId == origin.toolCallId) {
            "Command SDK grant does not match its immutable task identity"
        }
    }
}
