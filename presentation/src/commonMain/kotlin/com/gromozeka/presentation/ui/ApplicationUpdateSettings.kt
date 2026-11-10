package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gromozeka.client.RemoteDistributionService
import com.gromozeka.domain.model.ApplicationUpdatePhase
import com.gromozeka.remote.protocol.ApplicationUpdateInventory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object ApplicationUpdateLauncher {
    var open: (() -> Unit)? by mutableStateOf(null)
}

@Composable
internal fun ApplicationUpdateSettings(service: RemoteDistributionService) {
    val translation = LocalTranslation.current
    val scope = rememberCoroutineScope()
    var inventory by remember { mutableStateOf<ApplicationUpdateInventory?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    suspend fun refresh() { inventory = service.getUpdateInventory(); error = null }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { block(); refresh() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: failure::class.simpleName }
            finally { busy = false }
        }
    }
    LaunchedEffect(service) {
        while (true) {
            try { refresh() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: failure::class.simpleName }
            delay(10_000)
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(translation.text("updates.title"), style = MaterialTheme.typography.titleLarge)
        ApplicationUpdateLauncher.open?.let { open ->
            OutlinedButton(onClick = open) { Text(translation.text("updates.thisApp")) }
        }
        Text(translation.text("updates.description"))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (inventory?.catalog == null) Text(translation.text("updates.noCatalog"))
        inventory?.installations?.forEach { installation ->
            val update = installation.request
            val available = inventory?.catalog?.artifacts?.any {
                it.component == installation.component && it.platform == installation.platform && it.version != installation.version
            } == true
            HorizontalDivider()
            Text("${installation.label} · ${installation.component} · ${installation.version}", style = MaterialTheme.typography.titleMedium)
            Text("${installation.platform} · ${installation.lastSeenAt}", style = MaterialTheme.typography.bodySmall)
            installation.unsupportedReason?.let { Text(it) }
            update?.let {
                Text("${it.artifact.version}: ${translation.text("updates.phase.${it.phase.name}")}")
                it.message?.let { message -> Text(message) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && available && installation.unsupportedReason == null && update?.phase?.terminal != false,
                    onClick = { action { service.requestUpdate(installation.id) } }) { Text(translation.text("updates.request")) }
                if (update != null && !update.phase.terminal && update.phase !in setOf(ApplicationUpdatePhase.INSTALLING, ApplicationUpdatePhase.WAITING_FOR_USER)) {
                    TextButton(enabled = !busy, onClick = { action { service.cancelUpdate(installation.id) } }) { Text(translation.text("updates.cancel")) }
                }
            }
            Row {
                Switch(checked = installation.automatic, enabled = !busy && installation.unsupportedReason == null,
                    onCheckedChange = { enabled -> action { service.setAutomaticUpdates(installation.id, enabled) } })
                Text(translation.text("updates.automatic"), Modifier.padding(12.dp))
            }
        }
    }
}
