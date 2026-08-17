package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationTabLayout
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.ConversationTabLayoutRepository
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ActiveGenerationPublisher
import com.gromozeka.domain.service.ActiveGenerationSnapshot
import com.gromozeka.domain.service.ActiveGenerationStateSyncService
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeStateSyncService
import com.gromozeka.domain.service.ConversationTabLayoutStateSyncService
import com.gromozeka.domain.service.DeclarativeStateInvalidator
import com.gromozeka.domain.service.DeclarativeStateKey
import com.gromozeka.domain.service.DeclarativeStateChangePublisher
import com.gromozeka.domain.service.DeclarativeStateSyncService
import com.gromozeka.shared.uuid.uuid7
import com.gromozeka.statesync.StateSyncSnapshot
import com.gromozeka.statesync.StateSyncSource
import com.gromozeka.statesync.StateSyncSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Service
class ConversationRuntimeStateSyncApplicationService(
    runtimeCoordinator: ConversationRuntimeCoordinator,
    @param:Qualifier("applicationScope") scope: CoroutineScope,
) : ConversationRuntimeStateSyncService {
    private val source = StateSyncSource(
        scope = scope,
        sourceEpoch = uuid7(),
        loader = runtimeCoordinator::snapshot,
    )

    override suspend fun subscribe(key: Conversation.Id): StateSyncSubscription<Conversation.Id, ConversationRuntimeSnapshot> =
        source.subscribe(key)

    override suspend fun snapshot(key: Conversation.Id): StateSyncSnapshot<Conversation.Id, ConversationRuntimeSnapshot> =
        source.snapshot(key)

    override suspend fun invalidate(key: Conversation.Id) {
        source.invalidate(key)
    }
}

@Service
class ActiveGenerationStateSyncApplicationService(
    @param:Qualifier("applicationScope") scope: CoroutineScope,
) : ActiveGenerationStateSyncService, ActiveGenerationPublisher {
    private val mutex = Mutex()
    private val snapshots = mutableMapOf<Conversation.Id, ActiveGenerationSnapshot>()
    private val source = StateSyncSource<Conversation.Id, ActiveGenerationSnapshot?>(
        scope = scope,
        sourceEpoch = uuid7(),
        loader = { key -> mutex.withLock { snapshots[key] } },
    )

    override suspend fun subscribe(
        key: Conversation.Id,
    ): StateSyncSubscription<Conversation.Id, ActiveGenerationSnapshot?> = source.subscribe(key)

    override suspend fun snapshot(
        key: Conversation.Id,
    ): StateSyncSnapshot<Conversation.Id, ActiveGenerationSnapshot?> = source.snapshot(key)

    override suspend fun invalidate(key: Conversation.Id) {
        source.invalidate(key)
    }

    override suspend fun publish(snapshot: ActiveGenerationSnapshot) {
        val accepted = mutex.withLock {
            val current = snapshots[snapshot.conversationId]
            val staleGeneration = current != null &&
                current.generationId != snapshot.generationId &&
                current.startedAt > snapshot.startedAt
            val staleUpdate = current != null &&
                current.generationId == snapshot.generationId &&
                current.updatedAt > snapshot.updatedAt
            if (staleGeneration || staleUpdate) {
                false
            } else {
                snapshots[snapshot.conversationId] = snapshot
                true
            }
        }
        if (accepted) {
            source.invalidate(snapshot.conversationId)
        }
    }

    override suspend fun clear(
        conversationId: Conversation.Id,
        generationId: String,
    ) {
        val removed = mutex.withLock {
            snapshots[conversationId]
                ?.takeIf { it.generationId == generationId }
                ?.let {
                    snapshots.remove(conversationId)
                    true
                }
                ?: false
        }
        if (removed) {
            source.invalidate(conversationId)
        }
    }
}

@Service
class ConversationTabLayoutStateSyncApplicationService(
    repository: ConversationTabLayoutRepository,
    @param:Qualifier("applicationScope") scope: CoroutineScope,
) : ConversationTabLayoutStateSyncService {
    private val source = StateSyncSource(
        scope = scope,
        sourceEpoch = uuid7(),
        loader = repository::load,
    )

    override suspend fun subscribe(key: User.Id): StateSyncSubscription<User.Id, ConversationTabLayout> =
        source.subscribe(key)

    override suspend fun snapshot(key: User.Id): StateSyncSnapshot<User.Id, ConversationTabLayout> =
        source.snapshot(key)

    override suspend fun invalidate(key: User.Id) {
        source.invalidate(key)
    }
}

@Service
class DeclarativeStateSyncApplicationService(
    @param:Qualifier("applicationScope") scope: CoroutineScope,
) : DeclarativeStateSyncService, DeclarativeStateInvalidator {
    private val source = StateSyncSource<DeclarativeStateKey, Unit>(
        scope = scope,
        sourceEpoch = uuid7(),
        loader = { Unit },
    )

    override suspend fun subscribe(key: DeclarativeStateKey): StateSyncSubscription<DeclarativeStateKey, Unit> =
        source.subscribe(key)

    override suspend fun snapshot(key: DeclarativeStateKey): StateSyncSnapshot<DeclarativeStateKey, Unit> =
        source.snapshot(key)

    override suspend fun invalidate(key: DeclarativeStateKey) {
        source.invalidate(key)
    }
}

data class DeclarativeStateChangedEvent(
    val keys: Set<DeclarativeStateKey>,
)

@Service
class SpringDeclarativeStateChangePublisher(
    private val eventPublisher: ApplicationEventPublisher,
) : DeclarativeStateChangePublisher {
    override fun publish(vararg keys: DeclarativeStateKey) {
        if (keys.isNotEmpty()) {
            eventPublisher.publishEvent(DeclarativeStateChangedEvent(keys.toSet()))
        }
    }
}

@Service
class DeclarativeStateChangedEventListener(
    private val invalidator: DeclarativeStateInvalidator,
    @param:Qualifier("applicationScope") private val scope: CoroutineScope,
) {
    @TransactionalEventListener(
        phase = TransactionPhase.AFTER_COMMIT,
        fallbackExecution = true,
    )
    fun onChanged(event: DeclarativeStateChangedEvent) {
        scope.launch {
            event.keys.forEach { invalidator.invalidate(it) }
        }
    }
}
