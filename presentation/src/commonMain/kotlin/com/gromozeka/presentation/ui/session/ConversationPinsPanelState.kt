package com.gromozeka.presentation.ui.session

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.input.TextFieldValue
import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.ConversationPinService
import com.gromozeka.domain.service.ProjectAccessDeniedException
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect

/** Client-local, UI-dispatcher-confined state. Keep it outside a tab/panel's visibility or composition key. */
@Stable
class ConversationPinsPanelState {
    private val conversations = mutableStateMapOf<Conversation.Id, PinsPanelSnapshot>()

    fun beginCreate(conversationId: Conversation.Id, text: String = "", source: ConversationPinSource? = null): Boolean {
        if (hasDraft(conversationId)) return false
        change(conversationId) { it.copy(draft = PinTextDraft(uuid7(), TextFieldValue(text), creationKey = uuid7(), source = source), sourceFailed = false) }
        return true
    }

    fun hasDraft(conversationId: Conversation.Id): Boolean = snapshot(conversationId).draft != null

    /** The parent could not read a message. Never retain its exception, preview or private source details. */
    fun sourceLoadFailed(conversationId: Conversation.Id) { change(conversationId) { it.copy(sourceFailed = true) } }

    internal fun snapshot(id: Conversation.Id): PinsPanelSnapshot = conversations[id] ?: PinsPanelSnapshot()
    private inline fun change(id: Conversation.Id, transform: (PinsPanelSnapshot) -> PinsPanelSnapshot) {
        conversations[id] = transform(snapshot(id))
    }

    internal fun retry(id: Conversation.Id) { change(id) { it.copy(retry = it.retry + 1) } }

    internal suspend fun observe(id: Conversation.Id, service: ConversationPinService, observation: Any) {
        currentCoroutineContext().ensureActive()
        change(id) { it.copy(observation = observation, load = PinsLoad.LOADING, loadProblem = null, pins = emptyList()) }
        try {
            service.observe(id).collect { pins ->
                currentCoroutineContext().ensureActive()
                // Do not truncate a malformed catalog or mix another Conversation into this panel.
                require(pins.size <= ConversationPinLimits.MAX_PINS_PER_CONVERSATION)
                require(pins.all { it.text.length <= ConversationPinLimits.MAX_TEXT_LENGTH })
                require(pins.sumOf { it.text.length.toLong() } <= ConversationPinLimits.MAX_TOTAL_TEXT_LENGTH)
                require(pins.all { it.conversationId == id } && pins.map { it.id }.distinct().size == pins.size)
                val ordered = pins.sortedWith(compareBy({ it.ordinal }, { it.id.value }))
                if (snapshot(id).observation === observation) change(id) {
                    it.copy(pins = ordered, load = PinsLoad.READY, loadProblem = null)
                }
            }
            if (snapshot(id).observation === observation && snapshot(id).load == PinsLoad.LOADING)
                change(id) { it.copy(load = PinsLoad.FAILED, loadProblem = PinProblem.READ_FAILED) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (snapshot(id).observation === observation) change(id) {
                it.copy(load = PinsLoad.FAILED, loadProblem = if (failure is ProjectAccessDeniedException) PinProblem.ACCESS_DENIED else PinProblem.READ_FAILED)
            }
        } finally {
            stopObserving(id, observation)
        }
    }

    /** Called by composition disposal as well as cancellation, fencing even a late/non-cooperative read. */
    internal fun stopObserving(id: Conversation.Id, observation: Any) {
        if (snapshot(id).observation === observation) change(id) { it.copy(observation = null) }
    }

    internal fun beginEdit(id: Conversation.Id, pin: ConversationPin): Boolean {
        if (pin.conversationId != id || hasDraft(id)) return false
        change(id) { it.copy(draft = PinTextDraft(uuid7(), TextFieldValue(pin.text), pinId = pin.id,
            expectedRevision = pin.revision, source = pin.source), sourceFailed = false) }
        return true
    }

    internal fun edit(id: Conversation.Id, value: TextFieldValue) {
        change(id) { view ->
            val draft = view.draft ?: return@change view
            view.copy(draft = draft.copy(value = value, editVersion = draft.editVersion + if (value.text == draft.value.text) 0 else 1,
                problem = if (value.text == draft.value.text) draft.problem else null))
        }
    }

    internal fun discard(id: Conversation.Id) { change(id) { it.copy(draft = null) } }

    internal fun requestDelete(id: Conversation.Id, pin: ConversationPin) {
        if (pin.conversationId == id && snapshot(id).operation == null)
            change(id) { it.copy(deleteTarget = pin, deleteProblem = null) }
    }

    internal fun dismissDelete(id: Conversation.Id) {
        if (snapshot(id).operation == null) change(id) { it.copy(deleteTarget = null, deleteProblem = null) }
    }

    /** Each explicit click makes at most one write. A failed/unknown create never gets a new key. */
    internal suspend fun save(id: Conversation.Id, service: ConversationPinService, expectedDraftId: String? = null, actionVersion: Long? = null) {
        currentCoroutineContext().ensureActive()
        val captured = snapshot(id).draft ?: return
        if (expectedDraftId != null && captured.id != expectedDraftId) return
        if (actionVersion != null && snapshot(id).actions != actionVersion) return
        if (snapshot(id).operation != null) return
        try { ConversationPinLimits.validateText(captured.value.text) }
        catch (failure: IllegalArgumentException) { draftProblem(id, captured.id, PinProblem.INVALID_TEXT); return }
        catch (failure: ConversationPinLimitException) { draftProblem(id, captured.id, PinProblem.LIMIT); return }
        val operation = Any()
        change(id) { it.copy(operation = operation, actions = it.actions + 1, draft = captured.copy(problem = null)) }
        try {
            val result = if (captured.pinId == null) {
                service.create(id, captured.value.text, captured.source, requireNotNull(captured.creationKey))
            } else {
                service.update(id, captured.pinId, requireNotNull(captured.expectedRevision), captured.value.text)
            }
            currentCoroutineContext().ensureActive()
            if (result.conversationId != id || (captured.pinId != null && result.id != captured.pinId)) throw ConversationPinOperationFailedException()
            val current = snapshot(id).draft
            if (snapshot(id).operation === operation && current != null && current.id == captured.id) change(id) {
                // Acknowledgments do not overwrite later typing. A newly created pin becomes an edit
                // of that exact result if typing continued while the create was in flight.
                val newer = it.pins.any { pin -> pin.id == result.id && pin.revision > result.revision }
                it.copy(draft = if (current.editVersion == captured.editVersion && !newer) null else current.copy(
                    pinId = result.id, expectedRevision = result.revision, source = result.source,
                    problem = if (newer) PinProblem.CONFLICT else null,
                ))
            }
            // The list remains authoritative observe() state, never a stale mutation-result upsert.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            draftProblem(id, captured.id, problem(failure, creating = captured.pinId == null))
        } finally { finish(id, operation) }
    }

    /** Explicit, clearly labelled draft replacement; an intervening edit/new draft wins over this read. */
    internal suspend fun reloadDraft(id: Conversation.Id, service: ConversationPinService, expectedDraftId: String? = null, actionVersion: Long? = null) {
        currentCoroutineContext().ensureActive()
        val captured = snapshot(id).draft ?: return
        if (expectedDraftId != null && captured.id != expectedDraftId) return
        if (actionVersion != null && snapshot(id).actions != actionVersion) return
        val pinId = captured.pinId ?: return
        if (snapshot(id).operation != null) return
        val operation = Any()
        change(id) { it.copy(operation = operation, actions = it.actions + 1, draft = captured.copy(problem = null)) }
        try {
            val pin = service.get(id, pinId)
            currentCoroutineContext().ensureActive()
            if (pin == null) { draftProblem(id, captured.id, PinProblem.NOT_FOUND); return }
            require(pin.conversationId == id && pin.id == pinId)
            val current = snapshot(id).draft
            if (snapshot(id).operation === operation && current != null && current.id == captured.id) change(id) {
                it.copy(draft = when {
                    current.editVersion != captured.editVersion -> current.copy(problem = PinProblem.RELOAD_EDITED)
                    it.pins.any { observed -> observed.id == pin.id && observed.revision > pin.revision } -> current.copy(problem = PinProblem.CONFLICT)
                    else -> current.copy(value = TextFieldValue(pin.text), expectedRevision = pin.revision, source = pin.source, problem = null)
                })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            draftProblem(id, captured.id, if (failure is ProjectAccessDeniedException) PinProblem.ACCESS_DENIED else PinProblem.READ_FAILED)
        } finally { finish(id, operation) }
    }

    internal suspend fun confirmDelete(id: Conversation.Id, service: ConversationPinService, expectedTarget: ConversationPin? = null, actionVersion: Long? = null) {
        currentCoroutineContext().ensureActive()
        val captured = snapshot(id).deleteTarget ?: return
        if (expectedTarget != null && captured != expectedTarget) return
        if (actionVersion != null && snapshot(id).actions != actionVersion) return
        if (snapshot(id).operation != null) return
        val operation = Any()
        change(id) { it.copy(operation = operation, actions = it.actions + 1, deleteProblem = null) }
        try {
            service.delete(id, captured.id, captured.revision)
            currentCoroutineContext().ensureActive()
            if (snapshot(id).operation === operation) change(id) {
                it.copy(deleteTarget = null, deleteProblem = null,
                    draft = it.draft?.let { draft -> if (draft.pinId == captured.id) draft.copy(problem = PinProblem.NOT_FOUND) else draft })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (snapshot(id).operation === operation) change(id) { it.copy(deleteProblem = problem(failure)) }
        } finally { finish(id, operation) }
    }

    private fun draftProblem(id: Conversation.Id, draftId: String, problem: PinProblem) {
        change(id) { view -> if (view.draft?.id == draftId) view.copy(draft = view.draft.copy(problem = problem)) else view }
    }
    private fun finish(id: Conversation.Id, operation: Any) {
        if (snapshot(id).operation === operation) change(id) { it.copy(operation = null) }
    }
    private fun problem(failure: Exception, creating: Boolean = false) = when (failure) {
        is ConversationPinConflictException -> if (creating) PinProblem.CREATE_CONFLICT else PinProblem.CONFLICT
        is ConversationPinNotFoundException -> PinProblem.NOT_FOUND
        is ConversationPinLimitException -> PinProblem.LIMIT
        is ProjectAccessDeniedException -> PinProblem.ACCESS_DENIED
        is IllegalArgumentException -> PinProblem.INVALID_TEXT
        else -> PinProblem.WRITE_FAILED
    }
}

internal enum class PinsLoad { IDLE, LOADING, READY, FAILED }
internal enum class PinProblem { READ_FAILED, WRITE_FAILED, INVALID_TEXT, LIMIT, CONFLICT, CREATE_CONFLICT, NOT_FOUND, ACCESS_DENIED, RELOAD_EDITED }
internal data class PinTextDraft(
    val id: String,
    val value: TextFieldValue,
    val creationKey: String? = null,
    val pinId: ConversationPin.Id? = null,
    val expectedRevision: Long? = null,
    val source: ConversationPinSource? = null,
    val editVersion: Long = 0,
    val problem: PinProblem? = null,
)
internal data class PinsPanelSnapshot(
    val pins: List<ConversationPin> = emptyList(),
    val load: PinsLoad = PinsLoad.IDLE,
    val loadProblem: PinProblem? = null,
    val observation: Any? = null,
    val retry: Long = 0,
    val draft: PinTextDraft? = null,
    val operation: Any? = null,
    // Fences repeated callbacks from an obsolete UI frame, even when a failure returns immediately.
    val actions: Long = 0,
    val sourceFailed: Boolean = false,
    val deleteTarget: ConversationPin? = null,
    val deleteProblem: PinProblem? = null,
)
