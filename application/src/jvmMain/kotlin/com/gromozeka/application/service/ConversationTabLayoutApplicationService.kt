package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationTabLayout
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.ConversationTabLayoutRepository
import com.gromozeka.domain.service.ConversationTabLayoutService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service

@Service
class ConversationTabLayoutApplicationService(
    private val repository: ConversationTabLayoutRepository,
    private val conversationRepository: ConversationRepository,
) : ConversationTabLayoutService {
    private val mutex = Mutex()
    private val updates = MutableSharedFlow<ConversationTabLayout>(replay = 1)

    override suspend fun snapshot(): ConversationTabLayout = repository.load()

    override suspend fun open(conversationId: Conversation.Id): ConversationTabLayout =
        mutate { current ->
            require(conversationRepository.findById(conversationId) != null) {
                "Conversation not found: ${conversationId.value}"
            }
            if (conversationId in current.conversationIds) {
                current
            } else {
                current.next(current.conversationIds + conversationId)
            }
        }

    override suspend fun close(conversationId: Conversation.Id): ConversationTabLayout =
        mutate { current ->
            if (conversationId !in current.conversationIds) {
                current
            } else {
                current.next(current.conversationIds - conversationId)
            }
        }

    override fun observe(): Flow<ConversationTabLayout> = flow {
        val initial = snapshot()
        emit(initial)
        emitAll(updates.filter { it.revision > initial.revision })
    }

    private suspend fun mutate(
        transform: suspend (ConversationTabLayout) -> ConversationTabLayout,
    ): ConversationTabLayout = mutex.withLock {
        val current = repository.load()
        val updated = transform(current)
        if (updated == current) {
            return@withLock current
        }
        repository.save(updated).also { updates.emit(it) }
    }

    private fun ConversationTabLayout.next(
        conversationIds: List<Conversation.Id>,
    ): ConversationTabLayout =
        ConversationTabLayout(
            conversationIds = conversationIds,
            revision = revision + 1,
            updatedAt = Clock.System.now(),
        )
}
