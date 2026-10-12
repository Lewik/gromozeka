package com.gromozeka.presentation.ui.session

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.ConversationPinService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/** UI contract double with actual in-memory CRUD/CAS effects. Not a SQL or Server integration fixture. */
internal class FakeConversationPinService : ConversationPinService {
    data class Create(val conversationId: Conversation.Id, val text: String, val source: ConversationPinSource?, val key: String?)
    data class Update(val conversationId: Conversation.Id, val id: ConversationPin.Id, val revision: Long, val text: String)
    data class Delete(val conversationId: Conversation.Id, val id: ConversationPin.Id, val revision: Long)
    private val streams = mutableMapOf<Conversation.Id, MutableStateFlow<List<ConversationPin>>>()
    private val creations = mutableMapOf<Pair<Conversation.Id, String?>, Pair<Create, ConversationPin>>()
    val creates = mutableListOf<Create>()
    val updates = mutableListOf<Update>()
    val deletes = mutableListOf<Delete>()
    val gets = mutableListOf<Pair<Conversation.Id, ConversationPin.Id>>()
    val observations = mutableListOf<Conversation.Id>()
    var activeObservers = 0
    var listCalls = 0
    var readFailure: Exception? = null
    var createFailure: Exception? = null
    var updateFailure: Exception? = null
    var deleteFailure: Exception? = null
    var getFailure: Exception? = null
    var failAfterCreate: Exception? = null
    var observeGate: CompletableDeferred<Unit>? = null
    var createGate: CompletableDeferred<Unit>? = null
    var getGate: CompletableDeferred<Unit>? = null
    var updateGate: CompletableDeferred<Unit>? = null
    var afterUpdateGate: CompletableDeferred<Unit>? = null
    var deleteGate: CompletableDeferred<Unit>? = null
    var nonCooperativeCreate = false
    private var nextId = 0L
    private fun stream(id: Conversation.Id) = streams.getOrPut(id) { MutableStateFlow(emptyList()) }
    fun pins(id: Conversation.Id) = stream(id).value
    fun emit(id: Conversation.Id, pins: List<ConversationPin>) { stream(id).value = pins }
    override fun observe(conversationId: Conversation.Id): Flow<List<ConversationPin>> {
        observations += conversationId
        return flow {
            activeObservers++
            try {
                observeGate?.await()
                readFailure?.let { throw it }
                emitAll(stream(conversationId))
            } finally { activeObservers-- }
        }
    }
    override suspend fun list(conversationId: Conversation.Id): List<ConversationPin> {
        listCalls++; readFailure?.let { throw it }; return pins(conversationId)
    }
    override suspend fun get(conversationId: Conversation.Id, pinId: ConversationPin.Id): ConversationPin? {
        gets += conversationId to pinId
        val captured = pins(conversationId).firstOrNull { it.id == pinId }
        getGate?.await(); getFailure?.let { throw it }
        return captured
    }
    override suspend fun create(conversationId: Conversation.Id, text: String, source: ConversationPinSource?, creationKey: String?): ConversationPin {
        val call = Create(conversationId, text, source, creationKey)
        creates += call
        if (nonCooperativeCreate) withContext(NonCancellable) { createGate?.await() } else createGate?.await()
        createFailure?.let { throw it }
        creations[conversationId to creationKey]?.let { (previous, pin) ->
            if (previous != call) throw ConversationPinConflictException()
            return pin
        }
        ConversationPinLimits.validateText(text)
        val number = ++nextId
        val pin = ConversationPin(ConversationPin.Id("synthetic-pin-$number"), conversationId, text, number, 1,
            Instant.fromEpochMilliseconds(number), Instant.fromEpochMilliseconds(number), source)
        emit(conversationId, pins(conversationId) + pin)
        creations[conversationId to creationKey] = call to pin
        failAfterCreate?.let { throw it }
        return pin
    }
    override suspend fun update(conversationId: Conversation.Id, pinId: ConversationPin.Id, expectedRevision: Long, text: String): ConversationPin {
        updates += Update(conversationId, pinId, expectedRevision, text)
        updateGate?.await(); updateFailure?.let { throw it }
        val pin = pins(conversationId).firstOrNull { it.id == pinId } ?: throw ConversationPinNotFoundException()
        if (pin.revision != expectedRevision) throw ConversationPinConflictException()
        ConversationPinLimits.validateText(text)
        val updated = pin.copy(text = text, revision = pin.revision + 1)
        emit(conversationId, pins(conversationId).map { if (it.id == pinId) updated else it })
        afterUpdateGate?.await()
        return updated
    }
    override suspend fun delete(conversationId: Conversation.Id, pinId: ConversationPin.Id, expectedRevision: Long) {
        deletes += Delete(conversationId, pinId, expectedRevision)
        deleteGate?.await(); deleteFailure?.let { throw it }
        val pin = pins(conversationId).firstOrNull { it.id == pinId } ?: throw ConversationPinNotFoundException()
        if (pin.revision != expectedRevision) throw ConversationPinConflictException()
        emit(conversationId, pins(conversationId).filterNot { it.id == pinId })
    }
}

internal fun pinFixture(conversationId: Conversation.Id = Conversation.Id("pins-a"), text: String = "Pinned text", ordinal: Long = 1, revision: Long = 1) =
    ConversationPin(ConversationPin.Id("pin-$ordinal"), conversationId, text, ordinal, revision,
        Instant.fromEpochMilliseconds(0), Instant.fromEpochMilliseconds(0))
