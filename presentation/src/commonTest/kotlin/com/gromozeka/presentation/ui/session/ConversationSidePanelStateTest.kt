package com.gromozeka.presentation.ui.session

import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.ui.session.ConversationSidePanelTab.Section
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConversationSidePanelStateTest {
    private val a = Conversation.Id("conversation-a")
    private val b = Conversation.Id("conversation-b")

    @Test fun defaultsFollowTheLayoutRatherThanTheClientPlatform() {
        val state = ConversationSidePanelState()
        for (id in listOf(a, b)) {
            assertTrue(state.panel(id, compact = false).visible)
            assertFalse(state.panel(id, compact = true).visible)
            assertEquals(Section.RUNTIME, state.panel(id, compact = false).selected)
            assertEquals(Section.RUNTIME, state.panel(id, compact = true).selected)
        }
    }

    @Test fun conversationSwitchesRestoreBothVisibilityAndTheSelectedStandardTab() {
        val state = ConversationSidePanelState()
        val first = state.panel(a, compact = false)
        first.open(Section.PINS)
        first.hide()
        val second = state.panel(b, compact = false)
        second.open(Section.TASKS)

        assertSame(first, state.panel(Conversation.Id(a.value), compact = false))
        assertEquals(Section.PINS, first.selected)
        assertFalse(first.visible)
        assertEquals(Section.TASKS, second.selected)
        assertTrue(second.visible)
        first.toggle()
        assertTrue(first.visible)
        assertTrue(second.visible)
    }

    @Test fun everyStandardSectionBelongsToItsConversation() {
        val state = ConversationSidePanelState()
        for (section in Section.entries) {
            state.panel(a, compact = true).open(section)
            assertEquals(section, state.panel(a, compact = true).selected)
            assertEquals(Section.RUNTIME, state.panel(b, compact = true).selected)
            assertFalse(state.panel(b, compact = true).visible)
        }
    }

    @Test fun wideAndCompactKeepIndependentVisualSelectionsAndVisibility() {
        val state = ConversationSidePanelState()
        val wide = state.panel(a, compact = false)
        val compact = state.panel(a, compact = true)
        wide.open(ConversationSidePanelTab.VisualPanel("wide-visual"))
        wide.hide()
        compact.open(ConversationSidePanelTab.VisualPanel("compact-visual"))

        assertNotSame(wide, compact)
        assertSame(wide, state.panel(a, compact = false))
        assertSame(compact, state.panel(a, compact = true))
        assertEquals(ConversationSidePanelTab.VisualPanel("wide-visual"), wide.selected)
        assertFalse(wide.visible)
        assertEquals(ConversationSidePanelTab.VisualPanel("compact-visual"), compact.selected)
        assertTrue(compact.visible)
        assertEquals(Section.RUNTIME, state.panel(b, compact = true).selected)
    }

    @Test fun initialAndAddedVisualsNeverOpenOrSelectAPanel() {
        val state = ConversationSidePanelState()
        val wide = state.panel(a, compact = false)
        val compact = state.panel(a, compact = true)
        wide.open(Section.SLOTS)
        wide.hide()
        state.acceptVisuals(a, setOf("initial-visual"))
        state.acceptVisuals(a, setOf("initial-visual", "new-visual"))

        assertEquals(Section.SLOTS, wide.selected)
        assertFalse(wide.visible)
        assertEquals(Section.RUNTIME, compact.selected)
        assertFalse(compact.visible)
        compact.open(ConversationSidePanelTab.VisualPanel("initial-visual"))
        state.acceptVisuals(a, setOf("initial-visual", "new-visual", "another-visual"))
        assertEquals(ConversationSidePanelTab.VisualPanel("initial-visual"), compact.selected)
        assertTrue(compact.visible)
    }

    @Test fun remoteRemovalReconcilesBothLayoutsWithoutChangingVisibilityOrAnotherConversation() {
        val state = ConversationSidePanelState()
        val selected = ConversationSidePanelTab.VisualPanel("removed-visual")
        val wide = state.panel(a, compact = false)
        val compact = state.panel(a, compact = true)
        wide.open(selected)
        wide.hide()
        compact.open(selected)
        state.panel(b, compact = true).open(ConversationSidePanelTab.VisualPanel("other-visual"))
        state.acceptVisuals(a, emptySet())

        assertEquals(Section.RUNTIME, wide.selected)
        assertFalse(wide.visible)
        assertEquals(Section.RUNTIME, compact.selected)
        assertTrue(compact.visible)
        assertEquals(ConversationSidePanelTab.VisualPanel("other-visual"), state.panel(b, true).selected)
        assertTrue(state.panel(b, true).visible)
        state.acceptVisuals(a, setOf("removed-visual"))
        assertEquals(Section.RUNTIME, wide.selected)
    }

    @Test fun lateSnapshotForAnotherConversationDoesNotTouchTheCurrentPanel() {
        val state = ConversationSidePanelState()
        state.panel(a, true).open(ConversationSidePanelTab.VisualPanel("a-visual"))
        state.panel(b, true).open(Section.THREAD)
        state.acceptVisuals(a, emptySet())
        assertEquals(Section.THREAD, state.panel(b, true).selected)
        assertTrue(state.panel(b, true).visible)
    }

    @Test fun applicationSettingsAndSeparateClientsHaveTheirOwnState() {
        val first = ConversationSidePanelState()
        val second = ConversationSidePanelState()
        for (compact in listOf(false, true)) {
            assertFalse(first.settings(compact).visible)
            assertEquals(Section.SETTINGS, first.settings(compact).selected)
        }
        first.settings(false).open(Section.SETTINGS)
        first.panel(a, false).open(Section.MEMORY)
        first.panel(a, false).hide()
        assertFalse(first.settings(true).visible)
        assertTrue(second.panel(a, false).visible)
        assertEquals(Section.RUNTIME, second.panel(a, false).selected)
        assertFalse(second.settings(false).visible)
    }
}
