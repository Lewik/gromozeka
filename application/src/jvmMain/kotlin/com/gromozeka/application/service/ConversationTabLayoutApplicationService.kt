package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationTabLayout
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.ConversationTabLayoutRepository
import com.gromozeka.domain.service.UserConversationTabLayoutService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class ConversationTabLayoutApplicationService(
    private val repository: ConversationTabLayoutRepository,
    private val conversationRepository: ConversationRepository,
) : UserConversationTabLayoutService {
    private val mutex = Mutex()
    private val updatesByUser = ConcurrentHashMap<User.Id, MutableSharedFlow<ConversationTabLayout>>()

    override suspend fun snapshot(userId: User.Id): ConversationTabLayout = repository.load(userId)

    override suspend fun open(userId: User.Id, conversationId: Conversation.Id): ConversationTabLayout =
        mutate(userId) { current ->
            require(conversationRepository.findById(conversationId) != null) {
                "Conversation not found: ${conversationId.value}"
            }
            if (conversationId in current.conversationIds) {
                current
            } else {
                current.next(current.conversationIds + conversationId)
            }
        }

    override suspend fun close(userId: User.Id, conversationId: Conversation.Id): ConversationTabLayout =
        mutate(userId) { current ->
            if (conversationId !in current.conversationIds) {
                current
            } else {
                current.next(current.conversationIds - conversationId)
            }
        }

    override suspend fun removeConversation(conversationId: Conversation.Id) {
        mutex.withLock {
            repository.loadAll().forEach { (userId, current) ->
                if (conversationId !in current.conversationIds) {
                    return@forEach
                }
                val updated = current.next(current.conversationIds - conversationId)
                repository.save(userId, updated)
                updates(userId).emit(updated)
            }
        }
    }

    override fun observe(userId: User.Id): Flow<ConversationTabLayout> = flow {
        val initial = snapshot(userId)
        emit(initial)
        emitAll(updates(userId).filter { it.revision > initial.revision })
    }

    private suspend fun mutate(
        userId: User.Id,
        transform: suspend (ConversationTabLayout) -> ConversationTabLayout,
    ): ConversationTabLayout = mutex.withLock {
        val current = repository.load(userId)
        val updated = transform(current)
        if (updated == current) {
            return@withLock current
        }
        repository.save(userId, updated).also { updates(userId).emit(it) }
    }

    private fun updates(userId: User.Id): MutableSharedFlow<ConversationTabLayout> =
        updatesByUser.computeIfAbsent(userId) { MutableSharedFlow(replay = 1) }

    private fun ConversationTabLayout.next(
        conversationIds: List<Conversation.Id>,
    ): ConversationTabLayout =
        ConversationTabLayout(
            conversationIds = conversationIds,
            revision = revision + 1,
            updatedAt = Clock.System.now(),
        )
}
