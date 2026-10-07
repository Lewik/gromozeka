package com.gromozeka.presentation.ui.session

import androidx.compose.runtime.*
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.visual.Visual
import com.gromozeka.domain.visual.VisualPath
import kotlinx.serialization.json.*
import kotlinx.coroutines.launch

/** Client-local selection and editor drafts survive side-panel hiding and layout changes. */
@Stable
class VisualPanelState {
    private val selected = mutableStateMapOf<Conversation.Id, String?>()
    private val snapshots = mutableStateMapOf<Conversation.Id, List<Visual>>()
    private val drafts = mutableMapOf<String, VisualFormDraft>()
    private val highlights = mutableStateMapOf<String, com.gromozeka.domain.visual.VisualHighlightCommand>()
    private val seenHighlightCommands = linkedSetOf<String>()

    fun applyHighlight(command: com.gromozeka.domain.visual.VisualHighlightCommand) {
        if (!seenHighlightCommands.add(command.commandId)) return
        while (seenHighlightCommands.size > 256) seenHighlightCommands.remove(seenHighlightCommands.first())
        val current = snapshots[command.conversationId]?.firstOrNull { it.id == command.visualId }
        if (current != null && current.documentRevision > command.documentRevision) return
        if (command.elementIds.isEmpty()) highlights.remove(command.visualId)
        else highlights[command.visualId] = command
        while (highlights.size > 128) highlights.remove(highlights.keys.first())
    }

    fun highlightedIds(visual: Visual): Set<String> = highlights[visual.id]
        ?.takeIf { it.conversationId == visual.conversationId && it.documentRevision == visual.documentRevision }
        ?.elementIds?.toSet().orEmpty()

    fun highlightedVisualIds(conversationId: Conversation.Id): Set<String> = visuals(conversationId)
        .filter { highlightedIds(it).isNotEmpty() }.mapTo(mutableSetOf()) { it.id }

    /** Client-local acknowledgement; deliberately no service call or form mutation. */
    fun clearHighlights(visualId: String) { highlights.remove(visualId) }
    var loadError by mutableStateOf<String?>(null)

    fun visuals(conversationId: Conversation.Id): List<Visual> = snapshots[conversationId].orEmpty()
    fun selected(conversationId: Conversation.Id): String? = selected[conversationId]
    fun dirtyVisualIds(conversationId: Conversation.Id): Set<String> = visuals(conversationId)
        .filter { draft(it).hasLocalChanges }.mapTo(mutableSetOf()) { it.id }
    fun select(conversationId: Conversation.Id, id: String?) { selected[conversationId] = id }
    fun draft(visual: Visual): VisualFormDraft = drafts.getOrPut(visual.id) { VisualFormDraft(visual) }

    fun close(visual: Visual, service: com.gromozeka.domain.visual.VisualService, scope: kotlinx.coroutines.CoroutineScope, fallbackError: String) {
        val draft = draft(visual)
        if (draft.closing) return
        draft.closing = true
        scope.launch {
            try { service.close(visual.conversationId, visual.id) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { draft.error = error.message ?: fallbackError }
            finally { draft.closing = false }
        }
    }

    fun accept(conversationId: Conversation.Id, visuals: List<Visual>): Boolean {
        val previous = snapshots[conversationId].orEmpty()
        val added = visuals.filter { next -> previous.none { it.id == next.id } }
        previous.filter { old -> visuals.none { it.id == old.id } }.forEach { drafts.remove(it.id); highlights.remove(it.id) }
        snapshots[conversationId] = visuals
        visuals.forEach {
            draft(it).accept(it)
            if ((highlights[it.id]?.documentRevision ?: Long.MAX_VALUE) < it.documentRevision) highlights.remove(it.id)
        }
        if (added.isNotEmpty()) selected[conversationId] = added.last().id
        else if (selected[conversationId] != null && visuals.none { it.id == selected[conversationId] }) selected[conversationId] = null
        loadError = null
        return added.isNotEmpty()
    }
}

@Stable
class VisualFormDraft(initial: Visual) {
    var form by mutableStateOf(initial.state.getValue("form").jsonObject)
        private set
    var editSequence by mutableLongStateOf(0)
        private set
    var sending by mutableStateOf(false)
    var closing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var formRevision = initial.formRevision
    private var acceptedForm by mutableStateOf(initial.state.getValue("form").jsonObject)
    private var numberEditors by mutableStateOf<Set<String>>(emptySet())

    /** Compare against accepted server values, not against the last click or a keystroke counter. */
    val hasLocalChanges: Boolean
        get() {
            if (form == acceptedForm) return false
            var comparable = buildJsonObject { put("form", form) }
            val accepted = buildJsonObject { put("form", acceptedForm) }
            for (path in numberEditors) {
                val canonical = runCatching { VisualPath.get(path, accepted) }.getOrNull() ?: continue
                val draft = runCatching { VisualPath.get(path, comparable) }.getOrNull() ?: continue
                if (sameNumberEditorValue(draft, canonical)) comparable = VisualPath.setForm(comparable, path, canonical)
            }
            return comparable.getValue("form") != acceptedForm
        }
    private val submissions = linkedMapOf<String, Long>()

    /** Same accepted baseline and numeric-draft rules as the tab, scoped to one bound field. */
    fun isFieldDirty(path: String): Boolean {
        require(path.startsWith("form.")) { "Dirty indicators apply only to form fields" }
        val local = runCatching { VisualPath.get(path, buildJsonObject { put("form", form) }) }.getOrNull()
        val saved = runCatching { VisualPath.get(path, buildJsonObject { put("form", acceptedForm) }) }.getOrNull()
        if (local == saved) return false
        return path !in numberEditors || local == null || saved == null || !sameNumberEditorValue(local, saved)
    }

    fun state(visual: Visual): JsonObject = JsonObject(visual.state + ("form" to form))

    fun edit(visual: Visual, path: String, value: JsonElement, numberEditor: Boolean = false) {
        numberEditors = if (numberEditor) numberEditors + path else numberEditors - path
        form = VisualPath.setForm(state(visual), path, value).getValue("form").jsonObject
        editSequence++
        error = null
    }

    fun submitted(eventId: String, normalizedState: JsonObject) {
        form = normalizedState.getValue("form").jsonObject
        submissions[eventId] = editSequence
        while (submissions.size > 64) submissions.remove(submissions.keys.first())
        sending = true
        error = null
    }

    fun accept(visual: Visual) {
        if (visual.formRevision < formRevision) return
        acceptedForm = visual.state.getValue("form").jsonObject
        if (visual.formRevision == formRevision) return
        val submittedAt = visual.formEventId?.let(submissions::get)
        // A whole form is authoritative, even when equal to its previous saved value.
        // Only our own delayed echo may preserve typing performed after that click.
        if (submittedAt == null || editSequence <= submittedAt) form = acceptedForm
        visual.formEventId?.let(submissions::remove)
        formRevision = visual.formRevision
    }
}

private fun sameNumberEditorValue(draft: JsonElement, accepted: JsonElement): Boolean {
    if (draft == accepted) return true
    if (draft !is JsonPrimitive || draft == JsonNull) return false
    val text = draft.content.trim()
    if (text.isEmpty()) return accepted == JsonNull
    if (accepted !is JsonPrimitive || accepted.isString || accepted == JsonNull) return false
    val integer = text.toLongOrNull()
    val acceptedInteger = accepted.longOrNull
    if (integer != null && acceptedInteger != null) return integer == acceptedInteger
    val number = text.toDoubleOrNull() ?: return false
    val canonical = accepted.doubleOrNull ?: return false
    // Never hide a changed large integer by rounding both values through Double.
    return number.isFinite() && canonical.isFinite() &&
        kotlin.math.abs(number) <= 9_007_199_254_740_991.0 && number == canonical
}
