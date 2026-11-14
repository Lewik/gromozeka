package com.gromozeka.presentation.ui.session

import com.gromozeka.presentation.ui.CompactButton

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.selected

import androidx.compose.ui.graphics.Color

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.visual.Visual
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.NavigationTab
import com.gromozeka.presentation.ui.OptionalTooltip
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import org.jetbrains.compose.resources.DrawableResource

internal sealed interface ConversationSidePanelTab {
    enum class Section(val titleKey: String) : ConversationSidePanelTab {
        RUNTIME("runtime.title"),
        THREAD("session.thread.title"),
        TASKS("runtime.tasksTitle"),
        SLOTS("slots.title"),
        PINS("pins.title"),
        PARTICIPANTS("session.participants.title"),
        MEMORY("memory.actionItems.title"),
        SETTINGS("settingsTooltip"),
    }
    data class VisualPanel(val id: String) : ConversationSidePanelTab
}

@Composable
internal fun SidePanelToggle(panelVisible: Boolean, onTogglePanel: () -> Unit) {
    val translation = LocalTranslation.current
    val controls = GromozekaTheme.controls
    val spacing = GromozekaTheme.spacing
    val panelLabel = translation.text(if (panelVisible) "session.sidebar.hide" else "session.sidebar.show")
    CompactButton(
        onClick = onTogglePanel,
        modifier = Modifier.size(controls.minHeight).testTag("side-panel-toggle").semantics { selected = panelVisible },
        tooltip = panelLabel,
        contentPadding = PaddingValues(spacing.controlGap), elevation = null,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (panelVisible) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            contentColor = if (panelVisible) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.RuntimePanel, panelLabel, Modifier.size(controls.iconSize))
        }
    }
}

internal fun conversationSidePanelWidth(availableWidth: Dp): Dp = minOf(620.dp, availableWidth * 0.42f)

@Composable
internal fun ConversationPanelFrame(
    title: String,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = GromozekaTheme.spacing.contentPadding)
                .padding(top = GromozekaTheme.spacing.contentPadding), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(GromozekaTheme.spacing.panelPadding))
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = contentPadding).padding(bottom = contentPadding),
                verticalArrangement = Arrangement.spacedBy(GromozekaTheme.spacing.panelPadding), content = content)
        }
    }
}

@Composable
internal fun ConversationSidePanel(
    selected: ConversationSidePanelTab,
    onSelect: (ConversationSidePanelTab) -> Unit,
    conversationAvailable: Boolean,
    visuals: List<Visual>,
    dirtyVisualIds: Set<String>,
    highlightedVisualIds: Set<String>,
    onCloseVisual: (Visual) -> Unit,
    onClearVisualHighlights: (String) -> Unit,
    onCloseConversation: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val translation = LocalTranslation.current
    Surface(modifier.fillMaxSize().testTag("conversation-side-panel"), color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
        Column(Modifier.fillMaxSize()) {
            DisableSelection {
                Row(Modifier.fillMaxWidth().height(48.dp).background(MaterialTheme.colorScheme.surface)
                    .testTag("conversation-side-panel-tabs"), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()).selectableGroup()) {
                    ConversationSidePanelTab.Section.entries.filter { conversationAvailable || it == ConversationSidePanelTab.Section.SETTINGS }
                        .forEach { section ->
                            val title = translation.text(section.titleKey)
                            key(section) {
                                SelectedSidePanelTab(selected == section) { visibility ->
                                    OptionalTooltip(title) {
                                        NavigationTab(selected == section, false, { onSelect(section) },
                                            visibility.size(48.dp).testTag("side-panel-tab-${section.name.lowercase()}")) {
                                            Icon(section.icon(), title, Modifier.size(24.dp))
                                        }
                                    }
                                }
                            }
                        }
                    visuals.forEach { visual -> key(visual.id) {
                        SelectedSidePanelTab(selected == ConversationSidePanelTab.VisualPanel(visual.id)) { visibility ->
                            OptionalTooltip(visual.title) {
                                Box(visibility.width(180.dp).fillMaxHeight()) {
                                VisualTab(visual.id, visual.title,
                                    selected = selected == ConversationSidePanelTab.VisualPanel(visual.id),
                                    dirty = visual.id in dirtyVisualIds,
                                    highlighted = visual.id in highlightedVisualIds,
                                    onSelect = { onSelect(ConversationSidePanelTab.VisualPanel(visual.id)) },
                                    onClose = { onCloseVisual(visual) },
                                    onClearHighlight = { onClearVisualHighlights(visual.id) })
                                }
                            }
                        }
                    } }
                    }
                    if (conversationAvailable && onCloseConversation != null) {
                        OptionalTooltip(translation.closeTabTooltip) {
                            IconButton(onClick = onCloseConversation, modifier = Modifier.size(48.dp).testTag("close-conversation-tab")) {
                                Icon(Icons.Default.Close, translation.closeTabTooltip, Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun SelectedSidePanelTab(selected: Boolean, content: @Composable (Modifier) -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        if (selected) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    content(Modifier.bringIntoViewRequester(requester))
}

private fun ConversationSidePanelTab.Section.icon(): DrawableResource = when (this) {
    ConversationSidePanelTab.Section.RUNTIME -> Icons.Default.Bolt
    ConversationSidePanelTab.Section.THREAD -> Icons.Default.Edit
    ConversationSidePanelTab.Section.TASKS -> Icons.Default.Terminal
    ConversationSidePanelTab.Section.SLOTS -> Icons.Default.DeveloperBoard
    ConversationSidePanelTab.Section.PINS -> Icons.Default.Book
    ConversationSidePanelTab.Section.PARTICIPANTS -> Icons.Default.Person
    ConversationSidePanelTab.Section.MEMORY -> Icons.AutoMirrored.Filled.ListAlt
    ConversationSidePanelTab.Section.SETTINGS -> Icons.Default.Settings
}

@Composable
internal fun ConversationVisualPanel(
    visual: Visual,
    loadError: String?,
    content: @Composable (Visual) -> Unit,
) {
    androidx.compose.foundation.text.selection.SelectionContainer(Modifier.fillMaxSize()) {
        ConversationPanelFrame(visual.title, contentPadding = 0.dp) {
            loadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Box(Modifier.weight(1f).fillMaxWidth()) { content(visual) }
        }
    }
}

/** Highlight the entire native Tab hit area, not just the title's Text bounds. */
@Composable
internal fun VisualTab(
    id: String, title: String, selected: Boolean, dirty: Boolean, highlighted: Boolean,
    onSelect: () -> Unit, onClose: () -> Unit, onClearHighlight: () -> Unit,
) {
    VisualHighlightContainer(highlighted, "tab-$id", onClearHighlight,
        modifier = Modifier.fillMaxSize().testTag("visual-tab-container-$id"),
        shape = androidx.compose.ui.graphics.RectangleShape,
        boundedGlow = true,
    ) {
        NavigationTab(selected, false, onSelect, Modifier.fillMaxSize().testTag("visual-tab-$id")) {
            VisualTabTitle(id, title, dirty, onClose)
        }
    }
}

/** Host chrome: the title and close button keep identical bounds with or without the dirty dot. */
@Composable
internal fun VisualTabTitle(id: String, title: String, dirty: Boolean, onClose: () -> Unit) {
    val translation = LocalTranslation.current
    VisualDirtyBadge(dirty, "visual-dirty-$id", Modifier.testTag("visual-title-$id")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp).testTag("visual-tab-label-$id"),
                color = MaterialTheme.colorScheme.onSurface)
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp).testTag("visual-close-$id")) {
                Icon(Icons.Default.Close, contentDescription = translation.text("visuals.close"), modifier = Modifier.size(16.dp))
            }
        }
    }
}
