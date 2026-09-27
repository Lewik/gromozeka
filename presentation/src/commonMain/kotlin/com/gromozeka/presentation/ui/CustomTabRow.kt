package com.gromozeka.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import com.gromozeka.presentation.ui.viewmodel.TabViewModel

internal data class ConversationTabHeader(
    val conversationId: Conversation.Id,
    val title: String,
    val displayName: String = title,
    val isLoading: Boolean = false,
    val isUnread: Boolean = false,
)

@Composable
fun CustomTabRow(
    selectedTabIndex: Int,
    showTabsAtBottom: Boolean,
    isCompactLayout: Boolean = false,
    tabs: List<TabViewModel>,
    conversations: Map<Conversation.Id, Conversation>,
    unreadConversationIds: Set<Conversation.Id>,
    hoveredTabIndex: Int,
    onTabSelect: (Int?) -> Unit,
    onTabHover: (Int) -> Unit,
    onTabHoverExit: () -> Unit,
    onRenameConversation: suspend (Conversation.Id, String) -> Unit,
) {
    val translation = LocalTranslation.current
    val headers = tabs.map { tab ->
        val isLoading by tab.isWaitingForResponse.collectAsState()
        val conversation = conversations[tab.conversationId]
        ConversationTabHeader(
            conversationId = tab.conversationId,
            title = conversation?.effectiveDisplayName(translation) ?: translation.text("chat.navigation.conversationFallback"),
            displayName = conversation?.displayName.orEmpty(),
            isLoading = isLoading,
            isUnread = tab.conversationId in unreadConversationIds,
        )
    }
    var renaming by remember { mutableStateOf<ConversationTabHeader?>(null) }

    ConversationNavigationTabs(
        selectedTabIndex = selectedTabIndex,
        showTabsAtBottom = showTabsAtBottom,
        isCompactLayout = isCompactLayout,
        headers = headers,
        hoveredTabIndex = hoveredTabIndex,
        onTabSelect = onTabSelect,
        onTabHover = onTabHover,
        onTabHoverExit = onTabHoverExit,
        onRename = { renaming = it },
    )

    renaming?.let { header ->
        NameEditDialog(
            isOpen = true,
            currentName = header.displayName,
            title = translation.renameConversationTitle,
            label = translation.conversationNameLabel,
            maxLength = 255,
            onRename = { onRenameConversation(header.conversationId, it) },
            onDismiss = { renaming = null },
        )
    }
}

/** Fixed-size utility tabs; conversations use names on desktop and a picker on narrow screens. */
@Composable
internal fun ConversationNavigationTabs(
    selectedTabIndex: Int,
    showTabsAtBottom: Boolean,
    isCompactLayout: Boolean,
    headers: List<ConversationTabHeader>,
    hoveredTabIndex: Int,
    onTabSelect: (Int?) -> Unit,
    onTabHover: (Int) -> Unit,
    onTabHoverExit: () -> Unit,
    onRename: (ConversationTabHeader) -> Unit,
    modifier: Modifier = Modifier,
) {
    val translation = LocalTranslation.current
    BoxWithConstraints(modifier.fillMaxWidth().testTag(UiTestTag.TabRow.value)) {
        val compact = isCompactLayout || maxWidth < 600.dp
        Row(Modifier.fillMaxWidth().height(48.dp).background(MaterialTheme.colorScheme.surface).selectableGroup()) {
            val utilityTabs = listOf(
                Triple(Icons.Default.Folder, translation.text("projectsTabTooltip"), UiTestTag.ProjectsTab.value),
                Triple(Icons.Default.Face, translation.text("chat.navigation.agents"), UiTestTag.AgentsTab.value),
                Triple(Icons.Default.Settings, translation.text("settingsTooltip"), UiTestTag.SettingsTab.value),
                Triple(Icons.Default.VoiceWave, translation.text("chat.navigation.live"), UiTestTag.LiveTab.value),
            )
            utilityTabs.forEachIndexed { index, (icon, title, tag) ->
                OptionalTooltip(title) {
                    NavigationTab(
                        selected = selectedTabIndex == index,
                        showTabsAtBottom = showTabsAtBottom,
                        onClick = { onTabSelect(if (index == 0) null else -index) },
                        modifier = Modifier.size(48.dp).testTag(tag),
                    ) { Icon(icon, contentDescription = title, modifier = Modifier.size(24.dp)) }
                }
            }
            if (headers.isNotEmpty()) {
                if (compact) {
                    ConversationTabPicker(
                        headers, selectedTabIndex, showTabsAtBottom, onTabSelect, onRename,
                        Modifier.weight(1f).fillMaxHeight(),
                    )
                } else {
                    headers.forEachIndexed { index, header ->
                        key(header.conversationId) {
                            NavigationTab(
                                selected = selectedTabIndex == index + 4,
                                showTabsAtBottom = showTabsAtBottom,
                                onClick = { onTabSelect(index) },
                                modifier = Modifier.weight(1f).fillMaxHeight()
                                    .onTabHover({ onTabHover(index) }, onTabHoverExit)
                                    .testTag(UiTestTag.SessionTab(index).value),
                            ) {
                                Text(header.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (header.isUnread) ConversationUnreadBadge(header)
                                // Both slots stay allocated: neither loading nor hover moves the title.
                                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                    if (hoveredTabIndex == index) IconButton(
                                        onClick = { onRename(header) },
                                        modifier = Modifier.size(20.dp).testTag(UiTestTag.SessionTabRename(index).value),
                                    ) { Icon(Icons.Default.Edit, translation.text("chat.navigation.renameTab"), Modifier.size(16.dp)) }
                                }
                                ConversationActivitySlot(header, Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationTabPicker(
    headers: List<ConversationTabHeader>,
    selectedTabIndex: Int,
    showTabsAtBottom: Boolean,
    onTabSelect: (Int?) -> Unit,
    onRename: (ConversationTabHeader) -> Unit,
    modifier: Modifier,
) {
    val translation = LocalTranslation.current
    var expanded by remember { mutableStateOf(false) }
    val current = headers.getOrNull(selectedTabIndex - 4)
    Box(modifier) {
        NavigationTab(
            selected = current != null,
            showTabsAtBottom = showTabsAtBottom,
            onClick = { expanded = true },
            modifier = Modifier.fillMaxSize().testTag("conversation-tab-picker"),
        ) {
            Text(current?.title ?: translation.text("chat.navigation.conversations"), Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (headers.any { it.isUnread }) Badge(Modifier.padding(horizontal = 4.dp).testTag("conversation-picker-unread"))
            ConversationActivitySlot(current, Modifier.size(16.dp))
            Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 240.dp, max = 360.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 1f),
        ) {
            headers.forEachIndexed { index, header ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(header.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (header == current) FontWeight.SemiBold else FontWeight.Normal)
                            if (header.isUnread) ConversationUnreadBadge(header)
                        }
                    },
                    onClick = { expanded = false; onTabSelect(index) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ConversationActivitySlot(header, Modifier.size(16.dp))
                            IconButton(
                                onClick = { expanded = false; onRename(header) },
                                modifier = Modifier.size(40.dp).testTag(UiTestTag.SessionTabRename(index).value),
                            ) { Icon(Icons.Default.Edit, translation.text("chat.navigation.renameTab"), Modifier.size(18.dp)) }
                        }
                    },
                    modifier = Modifier.testTag(UiTestTag.SessionTab(index).value)
                        .semantics { selected = header == current },
                )
            }
        }
    }
}

@Composable
private fun ConversationActivitySlot(header: ConversationTabHeader?, modifier: Modifier) {
    Box(modifier.testTag("conversation-activity-slot:${header?.conversationId?.value.orEmpty()}"), contentAlignment = Alignment.Center) {
        if (header?.isLoading == true) GromozekaTabLoadingIndicator(Modifier.size(14.dp))
    }
}

@Composable
private fun ConversationUnreadBadge(header: ConversationTabHeader) {
    Badge(Modifier.padding(horizontal = 4.dp).testTag(UiTestTag.ConversationUnread(header.conversationId.value).value))
}

@Composable
private fun NavigationTab(
    selected: Boolean,
    showTabsAtBottom: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val indicator = MaterialTheme.colorScheme.primary
    Tab(
        selected = selected,
        onClick = onClick,
        selectedContentColor = MaterialTheme.colorScheme.onSurface,
        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.drawWithContent {
            drawContent()
            if (selected) {
                val height = 3.dp.toPx()
                drawRect(indicator, Offset(0f, if (showTabsAtBottom) 0f else size.height - height), Size(size.width, height))
            }
        },
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
