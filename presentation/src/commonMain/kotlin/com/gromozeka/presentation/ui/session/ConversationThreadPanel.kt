package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationContext
import com.gromozeka.domain.model.ConversationMessageSelection
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.presentation.ui.CompactButton
import com.gromozeka.presentation.ui.CompactButtonDefaults
import com.gromozeka.presentation.ui.GromozekaLoadingIndicator
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.ToggleButtonGroup
import com.gromozeka.presentation.ui.ToggleButtonOption
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import com.gromozeka.presentation.ui.viewmodel.MessageSquashUiState
import com.gromozeka.presentation.ui.viewmodel.TabViewModel
import com.gromozeka.presentation.ui.viewmodel.editableText
import com.gromozeka.presentation.ui.viewmodel.pinTextSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource

@Composable
internal fun ConversationThreadPanel(
    viewModel: TabViewModel,
    coroutineScope: CoroutineScope,
    onNewSession: () -> Unit,
    onForkSession: () -> Unit,
    onPinMessage: (Conversation.Message.Id) -> Unit,
    pinMessageEnabled: Boolean,
) {
    val translation = LocalTranslation.current
    val spacing = GromozekaTheme.spacing
    val messages by viewModel.allMessages.collectAsState()
    val selectedKinds by viewModel.selectedHistoryKinds.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val squash by viewModel.messageSquashState.collectAsState()
    val channel by viewModel.externalChannel.collectAsState()
    val running = squash is MessageSquashUiState.Running
    val protectedIds = remember(messages) { ConversationContext(messages).protectedMessageIds() }
    val selected = remember(messages, uiState.selectedMessageIds) {
        uiState.selectedMessageIds.singleOrNull()?.let { id -> messages.firstOrNull { it.id == id } }
    }
    ConversationPanelFrame(translation.text("session.thread.title"), Modifier.testTag("conversation-thread-panel")) {
        if (channel != null) {
            Text(translation.text("telegram.inputPolicy"))
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.controlGap), verticalArrangement = Arrangement.spacedBy(spacing.controlGap)) {
                    CompactButton(onClick = onNewSession, colors = CompactButtonDefaults.tonalColors()) { Text(translation.newSessionShort) }
                    CompactButton(onClick = onForkSession, colors = CompactButtonDefaults.tonalColors()) { Text(translation.forkButton) }
                }
                Text(translation.text("chat.selection.selectedCount", "count" to uiState.selectedMessageIds.size))
                val options = remember(translation) {
                    listOf(
                        ToggleButtonOption(Icons.Default.SelectAll, translation.text("chat.selection.toggleAll")),
                        ToggleButtonOption(Icons.Default.Person, translation.text("chat.selection.userMessages")),
                        ToggleButtonOption(Icons.Default.DeveloperBoard, translation.text("chat.selection.assistantMessages")),
                        ToggleButtonOption(Icons.Default.Psychology, translation.text("chat.selection.thinkingBlocks")),
                        ToggleButtonOption(Icons.Default.Build, translation.text("chat.selection.toolCalls")),
                        ToggleButtonOption(Icons.Default.ChatBubbleOutline, translation.text("chat.selection.plainMessages")),
                    )
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("thread-message-selection")) {
                    ToggleButtonGroup(options = options, selectedIndices = selectedKinds.map { it.ordinal }.toSet(),
                        onToggle = { viewModel.toggleHistorySelection(ConversationMessageSelection.entries[it]) })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.controlGap), verticalArrangement = Arrangement.spacedBy(spacing.controlGap)) {
                    ThreadActionButton(translation.text("runtime.editButton"), translation.text("chat.selection.editHint"), Icons.Default.Edit,
                        selected?.editableText() != null && selected.id !in protectedIds && !running,
                        { selected?.let { viewModel.startEditMessage(it.id) } }, Modifier.testTag(UiTestTag.EditSelectedMessageButton.value))
                    ThreadActionButton(translation.text("pins.pinMessage"), translation.text("pins.pinMessage"), Icons.Default.Book,
                        selected?.pinTextSnapshot() != null && pinMessageEnabled && !running,
                        { selected?.let { onPinMessage(it.id) } }, Modifier.testTag("pin-selected-message"))
                    ThreadActionButton(translation.text("chat.selection.concat"), translation.text("chat.selection.concatHint"), Icons.AutoMirrored.Filled.MergeType,
                        uiState.selectedMessageIds.size >= 2 && !running, { coroutineScope.launch { viewModel.squashSelectedMessages() } })
                    ThreadActionButton(translation.text("chat.selection.distill"), translation.text("chat.selection.distillHint"), Icons.Default.Compress,
                        uiState.selectedMessageIds.isNotEmpty() && !running, { coroutineScope.launch { viewModel.distillSelectedMessages() } })
                    ThreadActionButton(translation.text("chat.selection.summarize"), translation.text("chat.selection.summarizeHint"), Icons.AutoMirrored.Filled.Subject,
                        uiState.selectedMessageIds.isNotEmpty() && !running, { coroutineScope.launch { viewModel.summarizeSelectedMessages() } })
                    ThreadActionButton(translation.text("chat.selection.delete"), translation.text("chat.selection.deleteHint"), Icons.Default.Delete,
                        uiState.selectedMessageIds.isNotEmpty() && uiState.selectedMessageIds.none { it in protectedIds } && !running,
                        { coroutineScope.launch { viewModel.deleteSelectedMessages() } })
                }
                MessageSquashStatus(squash)
            }
        }
    }
}

@Composable
private fun ThreadActionButton(
    label: String, hint: String, icon: DrawableResource, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    CompactButton(onClick = onClick, enabled = enabled, modifier = modifier, tooltip = hint, colors = CompactButtonDefaults.tonalColors()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(GromozekaTheme.spacing.controlGap))
            Text(label, maxLines = 1)
        }
    }
}

@Composable
private fun MessageSquashStatus(state: MessageSquashUiState) {
    val localization = LocalTranslation.current
    val text = when (state) {
        MessageSquashUiState.Idle -> return
        is MessageSquashUiState.Running -> localization.text("chat.selection.operationRunning", "action" to state.squashType.actionTitle(localization))
        is MessageSquashUiState.Succeeded -> localization.text("chat.selection.operationComplete", "action" to state.squashType.actionTitle(localization))
        is MessageSquashUiState.Failed -> localization.text("chat.selection.operationFailed", "action" to state.squashType.actionTitle(localization), "message" to state.message.resolve(localization))
    }
    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .testTag(UiTestTag.MessageSquashStatus.value),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state is MessageSquashUiState.Running) {
            GromozekaLoadingIndicator(modifier = Modifier.size(16.dp))
        }
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (state is MessageSquashUiState.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun com.gromozeka.domain.model.SquashType.actionTitle(localization: Translation): String = when (this) {
    com.gromozeka.domain.model.SquashType.CONCATENATE -> localization.text("chat.selection.concat")
    com.gromozeka.domain.model.SquashType.DISTILL -> localization.text("chat.selection.distill")
    com.gromozeka.domain.model.SquashType.SUMMARIZE -> localization.text("chat.selection.summarize")
}
