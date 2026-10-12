package com.gromozeka.presentation.ui.session

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationPinService
import com.gromozeka.presentation.ui.*
import com.gromozeka.presentation.ui.icons.Icons
import kotlinx.coroutines.launch

/** A local native panel, not a Visual. The parent alone controls visibility and message-source loading. */
@Composable
fun ConversationPinsPanel(
    isVisible: Boolean,
    conversationId: Conversation.Id,
    service: ConversationPinService,
    state: ConversationPinsPanelState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    fullScreen: Boolean = false,
    slideFromRight: Boolean = false,
) {
    val translation = LocalTranslation.current
    val view = state.snapshot(conversationId)
    val scope = rememberCoroutineScope()
    val watch = remember(isVisible, conversationId, service, state, view.retry) { Any() }
    DisposableEffect(watch) {
        onDispose { state.stopObserving(conversationId, watch) }
    }
    LaunchedEffect(watch) {
        if (isVisible) state.observe(conversationId, service, watch)
    }
    AnimatedVisibility(
        visible = isVisible,
        enter = if (slideFromRight) slideInHorizontally(initialOffsetX = { it }) else expandHorizontally(),
        exit = if (slideFromRight) slideOutHorizontally(targetOffsetX = { it }) else shrinkHorizontally(),
        modifier = modifier,
    ) {
        Surface(
            modifier = (if (fullScreen) Modifier.fillMaxSize() else Modifier.width(400.dp).fillMaxHeight()).testTag("pins-panel"),
            color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(GromozekaTheme.spacing.contentPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(translation.text("pins.title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    CompactIconButton(onClick = onClose, icon = Icons.Default.Close,
                        contentDescription = translation.text("pins.hide"), modifier = Modifier.testTag("pins-close"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface))
                }
                if (view.sourceFailed) Text(translation.text("pins.sourceLoadFailed"), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("pins-source-error"))
                if (view.operation != null) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("pins-busy"))
                val draft = view.draft
                if (draft != null) key(conversationId, draft.id) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(translation.text(if (draft.pinId == null) "pins.new" else "pins.edit"), style = MaterialTheme.typography.titleMedium)
                        if (draft.pinId != null && view.load == PinsLoad.READY) {
                            val observed = view.pins.firstOrNull { it.id == draft.pinId }
                            when {
                                observed == null -> Text(translation.text("pins.deletedDraft"), modifier = Modifier.testTag("pins-remote-change"))
                                observed.revision != draft.expectedRevision -> Text(translation.text("pins.changedDraft"), modifier = Modifier.testTag("pins-remote-change"))
                            }
                        }
                        Text(translation.text("pins.text"), style = MaterialTheme.typography.labelLarge)
                        CompactTextField(value = draft.value, onValueChange = { state.edit(conversationId, it) },
                            modifier = Modifier.weight(1f).fillMaxWidth().testTag("pins-text"), minLines = 3,
                            // No max-length filter or normalization: shared validation runs on the exact input.
                            errorMessage = draft.problem?.let { pinProblemText(it) })
                        draft.problem?.let { PinError(it, "pins-draft-error") }
                        if (draft.pinId != null) TextButton(
                            onClick = { scope.launch { state.reloadDraft(conversationId, service, draft.id, view.actions) } }, enabled = view.operation == null,
                            modifier = Modifier.testTag("pins-reload"),
                        ) { Text(translation.text("pins.reload")) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompactButton(onClick = { scope.launch { state.save(conversationId, service, draft.id, view.actions) } }, enabled = view.operation == null,
                                modifier = Modifier.testTag("pins-save")) { Text(translation.text("pins.save")) }
                            CompactButton(onClick = { state.discard(conversationId) }, enabled = view.operation == null, colors = CompactButtonDefaults.tonalColors(),
                                modifier = Modifier.testTag("pins-discard")) { Text(translation.text("pins.discard")) }
                        }
                    }
                } else {
                    CompactButton(onClick = { state.beginCreate(conversationId) }, modifier = Modifier.testTag("pins-add")) {
                        Text(translation.text("pins.add"))
                    }
                    when (view.load) {
                        PinsLoad.IDLE, PinsLoad.LOADING -> Text(translation.text("pins.loading"), modifier = Modifier.testTag("pins-loading"))
                        PinsLoad.FAILED -> {
                            PinError(view.loadProblem ?: PinProblem.READ_FAILED, "pins-load-error")
                            CompactButton(onClick = { state.retry(conversationId) }, colors = CompactButtonDefaults.tonalColors(),
                                modifier = Modifier.testTag("pins-retry")) { Text(translation.text("pins.retry")) }
                        }
                        PinsLoad.READY -> if (view.pins.isEmpty()) {
                            Text(translation.text("pins.empty"), modifier = Modifier.testTag("pins-empty"))
                        } else LazyColumn(Modifier.weight(1f).testTag("pins-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(view.pins, key = { it.id.value }) { pin ->
                                OutlinedCard(Modifier.fillMaxWidth().testTag("pin-${pin.id.value}")) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(pin.text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("pin-preview-${pin.id.value}"))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(onClick = { state.beginEdit(conversationId, pin) }, modifier = Modifier.testTag("pin-edit-${pin.id.value}")) {
                                                Text(translation.text("pins.edit"))
                                            }
                                            TextButton(onClick = { state.requestDelete(conversationId, pin) }, enabled = view.operation == null,
                                                modifier = Modifier.testTag("pin-delete-${pin.id.value}")) { Text(translation.text("pins.delete")) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (draft != null && (view.load == PinsLoad.IDLE || view.load == PinsLoad.LOADING))
                    Text(translation.text("pins.loading"), modifier = Modifier.testTag("pins-loading"))
                // A read failure is still visible when a preserved draft is open, never presented as an empty list.
                if (draft != null && view.load == PinsLoad.FAILED) {
                    PinError(view.loadProblem ?: PinProblem.READ_FAILED, "pins-load-error")
                    TextButton(onClick = { state.retry(conversationId) }, modifier = Modifier.testTag("pins-retry")) { Text(translation.text("pins.retry")) }
                }
            }
        }
    }
    if (isVisible && view.deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { state.dismissDelete(conversationId) },
            modifier = Modifier.testTag("pins-delete-dialog"),
            title = { Text(translation.text("pins.deleteTitle")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(translation.text("pins.deletePrompt"))
                view.deleteProblem?.let { PinError(it, "pins-delete-error") }
            } },
            confirmButton = { TextButton(onClick = { scope.launch { state.confirmDelete(conversationId, service, view.deleteTarget, view.actions) } },
                enabled = view.operation == null, modifier = Modifier.testTag("pins-delete-confirm")) { Text(translation.text("pins.delete")) } },
            dismissButton = { TextButton(onClick = { state.dismissDelete(conversationId) }, enabled = view.operation == null,
                modifier = Modifier.testTag("pins-delete-cancel")) { Text(translation.text("pins.cancel")) } },
        )
    }
}

@Composable
private fun PinError(problem: PinProblem, tag: String) {
    Text(pinProblemText(problem), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(tag))
}

@Composable
private fun pinProblemText(problem: PinProblem): String {
    val translation = LocalTranslation.current
    return when (problem) {
        PinProblem.READ_FAILED -> translation.text("pins.readFailed")
        PinProblem.WRITE_FAILED -> translation.text("pins.writeFailed")
        PinProblem.INVALID_TEXT -> translation.text("pins.invalidText")
        PinProblem.LIMIT -> translation.text("pins.limit")
        PinProblem.CONFLICT -> translation.text("pins.conflict")
        PinProblem.CREATE_CONFLICT -> translation.text("pins.createConflict")
        PinProblem.NOT_FOUND -> translation.text("pins.notFound")
        PinProblem.ACCESS_DENIED -> translation.text("pins.accessDenied")
        PinProblem.RELOAD_EDITED -> translation.text("pins.reloadEdited")
    }
}
