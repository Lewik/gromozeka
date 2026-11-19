package com.gromozeka.presentation.ui.session

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gromozeka.domain.model.Conversation

@Stable
internal class ConversationSidePanelState {
    private val conversations = mutableStateMapOf<Conversation.Id, SidePanelLayouts>()
    private val settings = SidePanelLayouts(
        initialTab = ConversationSidePanelTab.Section.SETTINGS,
        initiallyVisibleOnWide = false,
    )

    fun panel(conversationId: Conversation.Id, compact: Boolean): SidePanelState =
        conversations.getOrPut(conversationId) { SidePanelLayouts() }.panel(compact)

    fun settings(compact: Boolean): SidePanelState = settings.panel(compact)

    fun acceptVisuals(conversationId: Conversation.Id, visualIds: Set<String>) {
        conversations[conversationId]?.acceptVisuals(visualIds)
    }
}

private class SidePanelLayouts(
    initialTab: ConversationSidePanelTab = ConversationSidePanelTab.Section.RUNTIME,
    initiallyVisibleOnWide: Boolean = true,
) {
    private val wide = SidePanelState(initiallyVisibleOnWide, initialTab)
    private val compact = SidePanelState(false, initialTab)

    fun panel(compact: Boolean): SidePanelState = if (compact) this.compact else wide

    fun acceptVisuals(visualIds: Set<String>) {
        wide.acceptVisuals(visualIds)
        compact.acceptVisuals(visualIds)
    }
}

@Stable
internal class SidePanelState(
    initiallyVisible: Boolean,
    initialTab: ConversationSidePanelTab,
) {
    var visible by mutableStateOf(initiallyVisible)
        private set
    var selected by mutableStateOf(initialTab)
        private set

    fun open(tab: ConversationSidePanelTab) {
        selected = tab
        visible = true
    }

    fun hide() { visible = false }
    fun toggle() { visible = !visible }

    internal fun acceptVisuals(visualIds: Set<String>) {
        val tab = selected
        if (tab is ConversationSidePanelTab.VisualPanel && tab.id !in visualIds) {
            selected = ConversationSidePanelTab.Section.RUNTIME
        }
    }
}
