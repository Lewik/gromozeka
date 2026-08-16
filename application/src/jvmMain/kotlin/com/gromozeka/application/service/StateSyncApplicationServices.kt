package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationTabLayout
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.ConversationTabLayoutRepository
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
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
