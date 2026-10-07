package com.gromozeka.presentation.ui.session

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.domain.visual.Visual
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class VisualPanelStateTest {
    private val initial = Visual(
        id = "visual-1", conversationId = Conversation.Id("conversation-1"), createdBy = User.Id("user-1"),
        document = "document", title = "Counter", state = obj("""{"form":{"query":""},"data":{"count":0}}"""),
        createdAt = Instant.fromEpochMilliseconds(0), updatedAt = Instant.fromEpochMilliseconds(0),
    )

    @Test fun dataUpdatesDoNotResetLocalTyping() {
        val draft = VisualFormDraft(initial)
        draft.edit(initial, "form.query", JsonPrimitive("local input"))
        val next = initial.copy(revision = 2, state = obj("""{"form":{"query":""},"data":{"count":1}}"""))
        draft.accept(next)
        assertEquals("local input", draft.state(next)["form"]!!.jsonObject["query"]!!.jsonPrimitive.content)
        assertEquals(1, draft.state(next)["data"]!!.jsonObject["count"]!!.jsonPrimitive.int)
    }

    @Test fun ownEchoDoesNotUndoTypingAfterTheClick() {
        val draft = VisualFormDraft(initial)
        draft.edit(initial, "form.query", JsonPrimitive("submitted"))
        draft.submitted("event-1", draft.state(initial))
        draft.sending = false // HTTP/WS receipt can precede the state-sync snapshot.
        draft.edit(initial, "form.query", JsonPrimitive("submitted and still typing"))
        draft.accept(initial.copy(revision = 2, formRevision = 2, formEventId = "event-1",
            state = obj("""{"form":{"query":"submitted"},"data":{"count":0}}""")))
        assertEquals("submitted and still typing", draft.form["query"]!!.jsonPrimitive.content)
    }

    @Test fun explicitFormReplacementAndOtherClientSubmissionWin() {
        val draft = VisualFormDraft(initial)
        draft.edit(initial, "form.query", JsonPrimitive("local"))
        val programmatic = initial.copy(revision = 2, formRevision = 2, formEventId = null,
            state = obj("""{"form":{"query":"reset"},"data":{}}"""))
        draft.accept(programmatic)
        assertEquals("reset", draft.form["query"]!!.jsonPrimitive.content)
        draft.edit(programmatic, "form.query", JsonPrimitive("new local"))
        draft.accept(programmatic.copy(revision = 3, formRevision = 3, formEventId = "other-client",
            state = obj("""{"form":{"query":"remote"},"data":{}}""")))
        assertEquals("remote", draft.form["query"]!!.jsonPrimitive.content)
        draft.accept(initial)
        assertEquals("remote", draft.form["query"]!!.jsonPrimitive.content)
    }

    @Test fun tabsArePerConversationAndNewVisualsAreSelectedOnce() {
        val panels = VisualPanelState()
        assertTrue(panels.accept(initial.conversationId, listOf(initial)))
        assertEquals(initial.id, panels.selected(initial.conversationId))
        panels.select(initial.conversationId, null)
        assertFalse(panels.accept(initial.conversationId, listOf(initial.copy(revision = 2))))
        assertNull(panels.selected(initial.conversationId))
        val second = initial.copy(id = "visual-2")
        assertTrue(panels.accept(initial.conversationId, listOf(initial, second)))
        assertEquals(second.id, panels.selected(initial.conversationId))
        assertNull(panels.selected(Conversation.Id("another-conversation")))
        panels.accept(initial.conversationId, listOf(initial))
        assertNull(panels.selected(initial.conversationId))
    }

    @Test fun completeFormReplacementRemovesOldFieldsAndResetsEvenUnchangedSavedValues() {
        val source = initial.copy(state = obj("""{"form":{"query":"","obsolete":{"nested":1}},"data":{}}"""))
        val draft = VisualFormDraft(source)
        draft.edit(source, "form.query", JsonPrimitive("local"))
        val changed = source.copy(revision = 2, formRevision = 2,
            state = obj("""{"form":{"query":"","limit":50},"data":{}}"""))
        draft.accept(changed)
        assertEquals(changed.state.getValue("form"), draft.form)
        assertFalse(draft.hasLocalChanges)
        draft.edit(changed, "form.query", JsonPrimitive("another local value"))
        draft.accept(changed.copy(revision = 3, formRevision = 3))
        assertEquals("", draft.form["query"]!!.jsonPrimitive.content)
        draft.accept(changed.copy(revision = 4, formRevision = 4, state = obj("""{"form":{},"data":{}}""")))
        assertEquals(obj("{}"), draft.form)
        assertFalse(draft.hasLocalChanges)
    }

    @Test fun coalescedOwnEchoAndDataOnlyUpdateDoNotUndoTyping() {
        val draft = VisualFormDraft(initial)
        draft.edit(initial, "form.query", JsonPrimitive("sent"))
        draft.submitted("event-1", draft.state(initial))
        draft.edit(initial, "form.query", JsonPrimitive("new input"))
        draft.accept(initial.copy(revision = 3, formRevision = 2, formEventId = "event-1",
            state = obj("""{"form":{"query":"sent"},"data":{"limit":50}}""")))
        assertEquals("new input", draft.form["query"]!!.jsonPrimitive.content)
        assertTrue(draft.hasLocalChanges)
    }

    @Test fun explicitReplacementAfterOwnEchoWinsEvenWhenSnapshotsCoalesce() {
        val draft = VisualFormDraft(initial)
        draft.edit(initial, "form.query", JsonPrimitive("sent"))
        draft.submitted("event-1", draft.state(initial))
        draft.edit(initial, "form.query", JsonPrimitive("new input"))
        draft.accept(initial.copy(revision = 3, formRevision = 3, formEventId = null,
            state = obj("""{"form":{"query":"sent"},"data":{}}""")))
        assertEquals("sent", draft.form["query"]!!.jsonPrimitive.content)
        assertFalse(draft.hasLocalChanges)
    }

    @Test fun dirtyDotTracksValuesAndIgnoresDataOnlyUpdates() {
        val draft = VisualFormDraft(initial)
        assertFalse(draft.hasLocalChanges)
        draft.edit(initial, "form.query", JsonPrimitive("local"))
        assertTrue(draft.hasLocalChanges)
        draft.accept(initial.copy(revision = 2, state = obj("""{"form":{"query":""},"data":{"count":10}}""")))
        assertTrue(draft.hasLocalChanges)
        draft.edit(initial, "form.query", JsonPrimitive(""))
        assertFalse(draft.hasLocalChanges)
    }

    @Test fun submissionKeepsDotUntilServerAcceptanceAndDoesNotHideNewTyping() {
        val draft = VisualFormDraft(initial)
        draft.edit(initial, "form.query", JsonPrimitive("sent"))
        draft.submitted("event-dot", draft.state(initial))
        assertTrue(draft.hasLocalChanges)
        draft.sending = false
        assertTrue(draft.hasLocalChanges) // A transport receipt is not the canonical state echo.
        val accepted = initial.copy(formRevision = 2, formEventId = "event-dot",
            state = obj("""{"form":{"query":"sent"},"data":{}}"""))
        draft.accept(accepted)
        assertFalse(draft.hasLocalChanges)
        draft.edit(accepted, "form.query", JsonPrimitive("sent again"))
        draft.submitted("event-next", draft.state(accepted))
        draft.edit(accepted, "form.query", JsonPrimitive("new typing"))
        draft.accept(accepted.copy(formRevision = 3, formEventId = "event-next",
            state = obj("""{"form":{"query":"sent again"},"data":{}}""")))
        assertTrue(draft.hasLocalChanges)
        draft.accept(initial) // A late older snapshot cannot move the accepted baseline backwards.
        assertTrue(draft.hasLocalChanges)
    }

    @Test fun numericEditorFormattingIsNotDirtyButDifferentSelectTypesAre() {
        val source = initial.copy(state = obj("""{"form":{"amount":1,"choice":1,"empty":null},"data":{}}"""))
        val draft = VisualFormDraft(source)
        draft.edit(source, "form.amount", JsonPrimitive("1.0"), numberEditor = true)
        assertFalse(draft.hasLocalChanges)
        draft.edit(source, "form.empty", JsonPrimitive(""), numberEditor = true)
        assertFalse(draft.hasLocalChanges)
        draft.edit(source, "form.amount", JsonPrimitive("-"), numberEditor = true)
        assertTrue(draft.hasLocalChanges)
        draft.edit(source, "form.amount", JsonPrimitive("1"), numberEditor = true)
        assertFalse(draft.hasLocalChanges)
        draft.edit(source, "form.choice", JsonPrimitive("1"))
        assertTrue(draft.hasLocalChanges)
    }

    @Test fun largeNumericValuesNeverHideChangesThroughRounding() {
        val source = initial.copy(state = obj("""{"form":{"rows":[{"value":9007199254740993}]},"data":{}}"""))
        val draft = VisualFormDraft(source)
        draft.edit(source, "form.rows[0].value", JsonPrimitive("9007199254740992"), numberEditor = true)
        assertTrue(draft.hasLocalChanges)
        draft.edit(source, "form.rows[0].value", JsonPrimitive("9007199254740993"), numberEditor = true)
        assertFalse(draft.hasLocalChanges)
    }

    @Test fun dirtyTabsSurviveSelectionChangesAndClearOnExplicitServerReset() {
        val panels = VisualPanelState()
        val second = initial.copy(id = "visual-2")
        panels.accept(initial.conversationId, listOf(initial, second))
        panels.draft(initial).edit(initial, "form.query", JsonPrimitive("draft"))
        panels.select(initial.conversationId, second.id)
        assertEquals(setOf(initial.id), panels.dirtyVisualIds(initial.conversationId))
        panels.accept(initial.conversationId, listOf(initial.copy(formRevision = 2,
            formEventId = null), second))
        assertTrue(panels.dirtyVisualIds(initial.conversationId).isEmpty())
    }

    @Test fun fieldDirtyFlagsAreIndependentForAllControlTypesAndNestedPaths() {
        val source = initial.copy(state = obj("""{"form":{"query":"saved","amount":1,"enabled":true,"choice":1,"volume":5,"notes":"text","rows":[{"value":"first"},{"value":"second"}]},"data":{}}"""))
        val draft = VisualFormDraft(source)
        val paths = listOf("form.query", "form.amount", "form.enabled", "form.choice", "form.volume", "form.notes", "form.rows[1].value")
        paths.forEach { assertFalse(draft.isFieldDirty(it), it) }
        draft.edit(source, "form.query", JsonPrimitive("changed"))
        draft.edit(source, "form.amount", JsonPrimitive("1.0"), numberEditor = true)
        draft.edit(source, "form.enabled", JsonPrimitive(false))
        draft.edit(source, "form.choice", JsonPrimitive("1"))
        draft.edit(source, "form.volume", JsonPrimitive(6))
        draft.edit(source, "form.rows[1].value", JsonPrimitive("new second"))
        for (path in listOf("form.query", "form.enabled", "form.choice", "form.volume", "form.rows[1].value")) assertTrue(draft.isFieldDirty(path), path)
        for (path in listOf("form.amount", "form.notes", "form.rows[0].value")) assertFalse(draft.isFieldDirty(path), path)
        assertFailsWith<IllegalArgumentException> { draft.isFieldDirty("data.value") }
        draft.edit(source, "form.query", JsonPrimitive("saved"))
        assertFalse(draft.isFieldDirty("form.query"))
        assertTrue(draft.hasLocalChanges)
    }

    @Test fun individualDotsWaitForAcceptanceAndKeepLaterTyping() {
        val source = initial.copy(state = obj("""{"form":{"query":"saved","notes":"old"},"data":{}}"""))
        val draft = VisualFormDraft(source)
        draft.edit(source, "form.query", JsonPrimitive("sent"))
        draft.edit(source, "form.notes", JsonPrimitive("sent notes"))
        draft.submitted("field-submit", draft.state(source))
        assertTrue(draft.isFieldDirty("form.query"))
        assertTrue(draft.isFieldDirty("form.notes"))
        draft.edit(source, "form.query", JsonPrimitive("newer input"))
        draft.accept(source.copy(formRevision = 2,
            formEventId = "field-submit",
            state = obj("""{"form":{"query":"sent","notes":"sent notes"},"data":{}}""")))
        assertTrue(draft.isFieldDirty("form.query"))
        assertFalse(draft.isFieldDirty("form.notes"))
        draft.accept(source.copy(formRevision = 3,
            formEventId = null,
            state = obj("""{"form":{"query":"reset","notes":"sent notes"},"data":{}}""")))
        assertFalse(draft.isFieldDirty("form.query"))
        assertFalse(draft.hasLocalChanges)
    }

    @Test fun wholeFormReplacementClearsAllFieldIndicators() {
        val source = initial.copy(state = obj("""{"form":{"query":"saved","enabled":true},"data":{}}"""))
        val draft = VisualFormDraft(source)
        draft.edit(source, "form.query", JsonPrimitive("local"))
        draft.edit(source, "form.enabled", JsonPrimitive(false))
        draft.accept(source.copy(formRevision = 2, formEventId = null,
            state = obj("""{"form":{"query":"saved","enabled":false},"data":{"new":1}}""")))
        assertFalse(draft.isFieldDirty("form.query"))
        assertFalse(draft.isFieldDirty("form.enabled"))
        assertFalse(draft.hasLocalChanges)
    }

    @Test fun sliderReturningToAnIntegerBaselineIsCleanDespiteDoubleEncoding() {
        val source = initial.copy(state = obj("""{"form":{"volume":2},"data":{}}"""))
        val draft = VisualFormDraft(source)
        draft.edit(source, "form.volume", JsonPrimitive(7.0), numberEditor = true)
        assertTrue(draft.isFieldDirty("form.volume"))
        assertTrue(draft.hasLocalChanges)
        draft.edit(source, "form.volume", JsonPrimitive(2.0), numberEditor = true)
        assertFalse(draft.isFieldDirty("form.volume"))
        assertFalse(draft.hasLocalChanges)
    }

    @Test fun highlightIsClientLocalDeduplicatedAndNeverBecomesFormState() {
        val first = VisualPanelState(); val second = VisualPanelState()
        first.accept(initial.conversationId, listOf(initial)); second.accept(initial.conversationId, listOf(initial))
        val command = com.gromozeka.domain.visual.VisualHighlightCommand("highlight-1", initial.conversationId, initial.id, initial.documentRevision, listOf("query"))
        first.applyHighlight(command); second.applyHighlight(command)
        first.select(initial.conversationId, null)
        assertEquals(setOf("query"), first.highlightedIds(initial))
        first.clearHighlights(initial.id)
        assertTrue(first.highlightedIds(initial).isEmpty())
        assertEquals(setOf("query"), second.highlightedIds(initial))
        first.applyHighlight(command)
        assertTrue(first.highlightedIds(initial).isEmpty())
        first.applyHighlight(command.copy(commandId = "highlight-2"))
        assertEquals(setOf("query"), first.highlightedIds(initial))
        assertEquals(initial.state, first.draft(initial).state(initial))
        assertFalse(first.draft(initial).hasLocalChanges)
        first.applyHighlight(command.copy(commandId = "highlight-clear", elementIds = emptyList()))
        assertTrue(first.highlightedIds(initial).isEmpty())
    }

    @Test fun highlightCanArriveBeforeSnapshotButNotSurviveAnUnrelatedDocumentReplacement() {
        val panels = VisualPanelState()
        val command = com.gromozeka.domain.visual.VisualHighlightCommand("future", initial.conversationId, initial.id, 2, listOf("query"))
        panels.applyHighlight(command)
        panels.accept(initial.conversationId, listOf(initial))
        assertTrue(panels.highlightedIds(initial).isEmpty())
        val next = initial.copy(documentRevision = 2)
        panels.accept(initial.conversationId, listOf(next))
        assertEquals(setOf("query"), panels.highlightedIds(next))
        panels.accept(initial.conversationId, listOf(next.copy(documentRevision = 3)))
        assertTrue(panels.highlightedIds(next).isEmpty())
        panels.applyHighlight(command.copy(commandId = "stale"))
        assertTrue(panels.highlightedIds(next).isEmpty())
    }

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
}
