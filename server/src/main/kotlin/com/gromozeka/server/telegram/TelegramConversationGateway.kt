package com.gromozeka.server.telegram

import com.gromozeka.application.service.ConversationArtifactApplicationService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.repository.TelegramConnectionRepository
import com.gromozeka.domain.service.*
import com.gromozeka.server.GromozekaRemoteAuthorization
import org.springframework.stereotype.Service

interface TelegramConversationGateway {
    suspend fun validate(invocation: TelegramInvocation): String
    suspend fun import(connection: TelegramConnection, message: TelegramInboxMessage)
    suspend fun cursor(binding: TelegramConversationBinding): Long
    suspend fun submit(invocation: TelegramInvocation)
    suspend fun events(invocation: TelegramInvocation): List<ConversationRuntimeEventLogEntry>
    suspend fun snapshot(binding: TelegramConversationBinding): ConversationRuntimeSnapshot
    suspend fun stop(invocation: TelegramInvocation): Boolean
}

class TelegramBindingRejected : IllegalStateException("Telegram conversation binding is unavailable")

fun interface TelegramBindingAccess {
    suspend fun requireUser(invocation: TelegramInvocation): User
}

@Service
class TelegramBindingAuthorization(
    private val identities: IdentityRepository,
    private val connections: TelegramConnectionRepository,
    private val authorization: GromozekaRemoteAuthorization,
    private val apiFactory: TelegramConnectionApiFactory,
) : TelegramBindingAccess {
    override suspend fun requireUser(invocation: TelegramInvocation): User {
        if (!apiFactory.serverEnabled) throw TelegramBindingRejected()
        val connection = connections.find(invocation.connectionId)?.takeIf { it.enabled && it.ownerUserId == invocation.ownerUserId }
            ?: throw TelegramBindingRejected()
        if (invocation.activationRevision != connection.activationRevision) throw TelegramBindingRejected()
        val binding = connection.bindings.singleOrNull { it.key == invocation.binding.key && it.enabled }
        if (binding != invocation.binding || invocation.route !in binding.routes) throw TelegramBindingRejected()
        val user = identities.findUserById(connection.ownerUserId)?.takeIf { it.canLogin && it.canUseAi } ?: throw TelegramBindingRejected()
        val conversation = try { authorization.requireConversation(user, binding.conversationId, ProjectPermission.WRITE) }
            catch (_: ProjectAccessDeniedException) { throw TelegramBindingRejected() }
        if (conversation.externalChannel != connection.channel(binding) || Conversation.Participant.Agent(invocation.route.agentId) !in conversation.participants ||
            Conversation.Participant.User(user.id) !in conversation.participants) throw TelegramBindingRejected()
        return user
    }
}

@Service
class TelegramRuntimeGateway(
    private val access: TelegramBindingAccess,
    private val ingress: ExternalConversationIngressService,
    private val coordinator: ConversationRuntimeCoordinator,
    private val artifacts: ConversationArtifactApplicationService,
    private val agents: AgentDomainService,
) : TelegramConversationGateway {
    override suspend fun validate(invocation: TelegramInvocation): String {
        access.requireUser(invocation)
        return agents.findById(invocation.route.agentId)?.name ?: throw TelegramBindingRejected()
    }
    override suspend fun import(connection: TelegramConnection, message: TelegramInboxMessage) {
        if (message.binding !in connection.bindings) throw TelegramBindingRejected()
        message.artifacts.forEach { artifacts.registerExternal(it) }
        ingress.importMessage(connection.channel(message.binding), message.message, message.replaceOriginalId)
    }
    override suspend fun cursor(binding: TelegramConversationBinding): Long = coordinator.snapshot(binding.conversationId).lastEventSequence
    override suspend fun submit(invocation: TelegramInvocation) {
        ingress.invokeAgent(ExternalConversationChannel("telegram", invocation.connectionId, invocation.binding.key),
            invocation.binding.conversationId, invocation.rootMessageId, invocation.route.agentId, access.requireUser(invocation), invocation.id)
    }
    override suspend fun events(invocation: TelegramInvocation): List<ConversationRuntimeEventLogEntry> =
        coordinator.listEventLogEntries(invocation.binding.conversationId, invocation.eventCursor, 100)
    override suspend fun snapshot(binding: TelegramConversationBinding): ConversationRuntimeSnapshot = coordinator.snapshot(binding.conversationId)
    override suspend fun stop(invocation: TelegramInvocation): Boolean =
        ingress.stop(invocation.binding.conversationId, ConversationRuntimeTurnId(invocation.id))
}

internal fun TelegramConnection.channel(binding: TelegramConversationBinding): ExternalConversationChannel =
    ExternalConversationChannel("telegram", id, binding.key)
