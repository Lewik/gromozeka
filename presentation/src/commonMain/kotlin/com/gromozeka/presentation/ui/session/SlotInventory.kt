package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.slot.*
import com.gromozeka.presentation.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class SlotHeaderOccupancy(val held: List<Long>, val pending: List<Long>)
internal fun slotHeaderOccupancy(views: List<SlotView>, conversationId: Conversation.Id) = SlotHeaderOccupancy(
    views.filter { it.snapshot.leases.any { lease -> lease.conversationId == conversationId && lease.active } }.map { it.snapshot.slot.number }.distinct().sorted(),
    views.filter { it.snapshot.requests.any { request -> request.conversationId == conversationId && request.state == SlotRequest.State.PENDING } }.map { it.snapshot.slot.number }.distinct().sorted(),
)

@Composable
fun SlotHeaderButton(service: SlotService, conversationId: Conversation.Id, onOpenConversation: (Conversation.Id) -> Unit) {
    val translation = LocalTranslation.current
    var views by remember(service) { mutableStateOf<List<SlotView>>(emptyList()) }
    var error by remember(service) { mutableStateOf<String?>(null) }
    var loading by remember(service) { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(service, reload) {
        loading = true
        try {
            service.observe().collect { views = it; loading = false; error = null }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { loading = false; error = failure.message ?: translation.text("slots.loadFailed") }
    }
    val occupancy = slotHeaderOccupancy(views, conversationId)
    val label = buildString {
        append(translation.text("slots.title"))
        if (occupancy.held.isNotEmpty()) append(": " + occupancy.held.joinToString(", "))
        if (occupancy.pending.isNotEmpty()) append(" · " + translation.text("slots.pending") + ": " + occupancy.pending.joinToString(", "))
        if (error != null) append(" !")
    }
    CompactButton(onClick = { open = true }, colors = CompactButtonDefaults.tonalColors(),
        modifier = Modifier.testTag("conversation-slots"), tooltip = label) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (open) SlotInventoryDialog(views, service, loading, error, onRetry = { reload++ },
        onClose = { open = false }, onOpenConversation = { open = false; onOpenConversation(it) })
}

@Composable
fun SlotInventoryDialog(
    views: List<SlotView>, service: SlotService, loading: Boolean, loadError: String?,
    onRetry: () -> Unit, onClose: () -> Unit, onOpenConversation: (Conversation.Id) -> Unit,
) {
    val translation = LocalTranslation.current
    val scope = rememberCoroutineScope()
    var actionError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Pair<SlotView, SlotLease>?>(null) }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true; actionError = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { actionError = failure.message ?: translation.text("slots.actionFailed") }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 820.dp).fillMaxHeight(0.86f).testTag("slot-inventory"),
            shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(translation.text("slots.title"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    CompactButton(onClick = onClose, colors = CompactButtonDefaults.tonalColors()) { Text(translation.text("common.close")) }
                }
                if (loading) Text(translation.text("slots.loading"))
                (actionError ?: loadError)?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                    CompactButton(onClick = onRetry, colors = CompactButtonDefaults.tonalColors()) { Text(translation.text("slots.retry")) }
                }
                if (!loading && views.isEmpty() && loadError == null) Text(translation.text("slots.empty"))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(views, key = { it.snapshot.slot.id }) { view ->
                        val slot = view.snapshot.slot
                        OutlinedCard(Modifier.fillMaxWidth().testTag("slot-${slot.number}")) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${translation.text("slots.title")} · ${slot.number}", style = MaterialTheme.typography.titleMedium)
                                Text(listOfNotNull(view.workerId, view.rootPath).joinToString(" · ").ifBlank { translation.text("slots.unavailable") },
                                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                if (view.snapshot.leases.isEmpty()) Text(translation.text("slots.free"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                view.snapshot.leases.forEach { lease ->
                                    HorizontalDivider()
                                    val title = view.conversationTitles[lease.conversationId.value]?.takeIf { it.isNotBlank() } ?: lease.conversationId.value
                                    Text(translation.text(if (lease.access == SlotAccess.WRITE) "slots.write" else "slots.read"), style = MaterialTheme.typography.labelLarge)
                                    TextButton(onClick = { onOpenConversation(lease.conversationId) },
                                        enabled = lease.conversationId.value in view.conversationTitles) { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                    Text("${translation.text("slots.acquired")}: ${lease.acquiredAt}", style = MaterialTheme.typography.bodySmall)
                                    if (lease.reclaimConfirmation != null) Text(translation.text("slots.reclaimRequested"), color = MaterialTheme.colorScheme.tertiary)
                                    CompactButton(onClick = { perform {
                                        val prepared = service.prepareReclaim(lease.id)
                                        if (prepared.active) confirmation = view to prepared
                                    } }, enabled = !busy, colors = CompactButtonDefaults.tonalColors(), modifier = Modifier.testTag("reclaim-${lease.id}")) {
                                        Text(translation.text("slots.reclaim"))
                                    }
                                }
                                view.snapshot.requests.forEach { request ->
                                    HorizontalDivider()
                                    Text("${translation.text("slots.pending")} · ${translation.text(if (request.access == SlotAccess.WRITE) "slots.write" else "slots.read")}")
                                    val title = view.conversationTitles[request.conversationId.value]?.takeIf { it.isNotBlank() } ?: request.conversationId.value
                                    TextButton(onClick = { onOpenConversation(request.conversationId) },
                                        enabled = request.conversationId.value in view.conversationTitles) { Text(title) }
                                    Text("${translation.text("slots.requested")}: ${request.requestedAt}", style = MaterialTheme.typography.bodySmall)
                                    CompactButton(onClick = { perform {
                                        val result = service.cancelRequest(request.id)
                                        if (result.state == SlotRequest.State.GRANTED) actionError = translation.text("slots.grantedInstead")
                                    } }, enabled = !busy, colors = CompactButtonDefaults.tonalColors()) { Text(translation.text("slots.cancelWait")) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    confirmation?.let { (view, lease) ->
        AlertDialog(onDismissRequest = { if (!busy) confirmation = null },
            title = { Text(translation.text("slots.reclaimTitle")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${translation.text("slots.title")} ${view.snapshot.slot.number} · ${view.conversationTitles[lease.conversationId.value].orEmpty()}")
                Text(translation.text("slots.reclaimWarning"))
                actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { CompactButton(onClick = { perform {
                service.confirmReclaim(lease.id, requireNotNull(lease.reclaimConfirmation).id)
                confirmation = null
            } }, enabled = !busy, modifier = Modifier.testTag("confirm-slot-reclaim")) { Text(translation.text("slots.reclaim")) } },
            dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !busy) { Text(translation.text("slots.cancel")) } })
    }
}
