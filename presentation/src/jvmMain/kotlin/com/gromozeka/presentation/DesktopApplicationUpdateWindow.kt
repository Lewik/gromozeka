package com.gromozeka.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.gromozeka.domain.model.ApplicationUpdatePhase
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.updates.ApplicationUpdateAgent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DesktopApplicationUpdateWindow(agent: ApplicationUpdateAgent, onClose: () -> Unit) {
    val translation = LocalTranslation.current
    val view by agent.view.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun action(block: suspend () -> Unit) { scope.launch {
        busy = true
        try { block(); error = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
        finally { busy = false }
    } }
    Window(onCloseRequest = onClose, title = translation.text("updates.title"), state = rememberWindowState(width = 520.dp, height = 410.dp)) {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(translation.text("updates.title"), style = MaterialTheme.typography.titleLarge)
                    view.installation?.let { Text(it.version) }
                    view.available?.let { Text("${translation.text("updates.available")}: ${it.version}") }
                    view.installation?.unsupportedReason?.let { Text(it) }
                    view.installation?.request?.let { Text(translation.text("updates.phase.${it.phase.name}")); it.message?.let { message -> Text(message) } }
                    (error ?: view.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy, onClick = { action { agent.checkNow() } }) { Text(translation.text("updates.check")) }
                        OutlinedButton(enabled = !busy && view.available != null && view.installation?.unsupportedReason == null && view.installation?.request?.phase?.terminal != false,
                            onClick = { action { agent.requestUpdate(); agent.checkNow() } }) { Text(translation.text("updates.download")) }
                    }
                    Button(enabled = !busy && view.installation?.request?.phase == ApplicationUpdatePhase.READY,
                        onClick = { action { agent.installReady() } }) { Text(translation.text("updates.restart")) }
                    Row {
                        Switch(checked = view.installation?.automatic == true, enabled = !busy && view.installation?.unsupportedReason == null,
                            onCheckedChange = { enabled -> action { agent.setAutomatic(enabled) } })
                        Text(translation.text("updates.automatic"), Modifier.padding(12.dp))
                    }
                    Text(translation.text("updates.clientExit"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
