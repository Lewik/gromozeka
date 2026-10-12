package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.ConversationPinRepository
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.service.*
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Service

/** UI authorization, independent of AI/tool eligibility. No turn, message, memory or queue dependencies. */
@Service
class ConversationPinApplicationService(
    private val repository: ConversationPinRepository,
    private val conversations: ConversationRepository,
    private val identities: IdentityRepository,
    private val access: ProjectAccessService,
    private val stateChanges: DeclarativeStateChangePublisher,
) {
    suspend fun list(actor: User.Id, conversationId: Conversation.Id): List<ConversationPin> = guarded {
        authorize(actor, conversationId, ProjectPermission.READ)
        repository.list(conversationId)
    }

    suspend fun get(actor: User.Id, conversationId: Conversation.Id, pinId: ConversationPin.Id): ConversationPin? = guarded {
        authorize(actor, conversationId, ProjectPermission.READ)
        repository.get(conversationId, pinId)
    }

    suspend fun create(
        actor: User.Id, conversationId: Conversation.Id, text: String,
        source: ConversationPinSource? = null, creationKey: String? = null,
    ): ConversationPin = guarded {
        authorize(actor, conversationId, ProjectPermission.WRITE)
        ConversationPinLimits.validateText(text)
        ConversationPinLimits.validateCreationKey(creationKey)
        ConversationPinLimits.validateSource(source)
        val result = repository.create(conversationId, actor, text, source, creationKey)
        // Repository has committed; no invalidation on an idempotent replay.
        if (result.created) stateChanges.publish(DeclarativeStateKey.conversationPins(conversationId))
        result.pin
    }

    suspend fun update(
        actor: User.Id, conversationId: Conversation.Id, pinId: ConversationPin.Id, expectedRevision: Long, text: String,
    ): ConversationPin = guarded {
        authorize(actor, conversationId, ProjectPermission.WRITE)
        require(expectedRevision > 0) { "Conversation pin revision must be positive" }
        ConversationPinLimits.validateText(text)
        val result = repository.update(conversationId, pinId, expectedRevision, text)
        if (result.revision != expectedRevision) stateChanges.publish(DeclarativeStateKey.conversationPins(conversationId))
        result
    }

    suspend fun delete(actor: User.Id, conversationId: Conversation.Id, pinId: ConversationPin.Id, expectedRevision: Long): Unit = guarded {
        authorize(actor, conversationId, ProjectPermission.WRITE)
        require(expectedRevision > 0) { "Conversation pin revision must be positive" }
        repository.delete(conversationId, pinId, expectedRevision)
        stateChanges.publish(DeclarativeStateKey.conversationPins(conversationId))
    }

    private suspend fun authorize(actor: User.Id, conversationId: Conversation.Id, permission: ProjectPermission) {
        val user = identities.findUserById(actor)
        if (user == null || user.status != User.Status.ACTIVE) throw ProjectAccessDeniedException()
        val conversation = conversations.findById(conversationId) ?: throw ProjectAccessDeniedException()
        access.requirePermission(actor, conversation.projectId, permission)
        if (Conversation.Participant.User(actor) !in conversation.participants) throw ProjectAccessDeniedException()
    }

    private suspend inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: ConversationPinException) { throw error }
    catch (denied: ProjectAccessDeniedException) { throw denied }
    catch (error: IllegalArgumentException) {
        // Only fixed validation messages cross this boundary, even if a storage decoder threw this type.
        throw IllegalArgumentException("Invalid conversation pin request")
    }
    catch (_: Exception) { throw ConversationPinOperationFailedException() }
}
