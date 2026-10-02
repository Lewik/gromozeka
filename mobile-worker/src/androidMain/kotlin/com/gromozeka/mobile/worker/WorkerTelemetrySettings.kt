package com.gromozeka.mobile.worker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gromozeka.domain.model.DeviceCollectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MainActivity.WorkerTelemetrySettings(
    runtime: MobileWorkerRuntime,
    status: MobileWorkerStatus,
    onStatus: (MobileWorkerStatus) -> Unit,
    onError: (WorkerMessage?) -> Unit,
) {
    val t = rememberWorkerStrings()
    val scope = rememberCoroutineScope()
    val configuration = status.telemetryConfiguration
    var usage by remember(configuration.applicationUsageEnabled) { mutableStateOf(configuration.applicationUsageEnabled) }
    var interval by remember(configuration.intervalSeconds) { mutableStateOf(configuration.intervalSeconds.toString()) }
    val state by AndroidWorkerTelemetryService.state.collectAsState()
    var usageAllowed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed) {
        if (resumed) while (true) {
            usageAllowed = AndroidWorkerTelemetrySource(applicationContext).usageAccess() == DeviceCollectionState.ACTIVE
            delay(2_000)
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onError(WorkerMessage(if (it) "permissionSaved" else "notificationsRequired"))
    }
    WorkerCard(t("telemetry")) {
        Text(t(if (!configuration.enabled) "off" else state.key))
        if (status.lastTelemetryCollectedAt != null) Text(t("lastCollection", "time" to workerTime(status.lastTelemetryCollectedAt)), style = MaterialTheme.typography.bodySmall)
        Text(t("usageStatus", "status" to t(if (usageAllowed) "allowed" else "notAllowed")))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = usage, onCheckedChange = { usage = it }, enabled = !configuration.enabled)
            Text(t("appUsage"))
        }
        if (!usageAllowed) TextButton(onClick = {
            runCatching { openWorkerSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageSpecific = true) }
                .onFailure { onError(it.workerMessage()) }
        }) { Text(t("usageAccess")) }
        WorkerDetails(leadingAction = {
            OutlinedButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        if (configuration.enabled) {
                            withContext(Dispatchers.IO) { runtime.configureTelemetry(configuration.copy(enabled = false)) }
                            AndroidWorkerTelemetryService.stop(applicationContext)
                        } else {
                            workerRequire(!usage || AndroidWorkerTelemetrySource(applicationContext).usageAccess() == DeviceCollectionState.ACTIVE, "usageRequired")
                            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                return@launch
                            }
                            workerRequire(AndroidWorkerTelemetryService.notificationsAllowed(applicationContext), "notificationsRequired")
                            val seconds = interval.toIntOrNull()
                            workerRequire(seconds != null && seconds in 10..900, "invalidInterval")
                            withContext(Dispatchers.IO) { runtime.configureTelemetry(WorkerTelemetryConfiguration(true, usage, seconds!!)) }
                            AndroidWorkerTelemetryService.start(applicationContext)
                        }
                        onStatus(withContext(Dispatchers.IO) { runtime.status() })
                        onError(null)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { onError(e.workerMessage()) }
                    finally { busy = false }
                }
            }) { Text(t(if (busy) "working" else if (configuration.enabled) "disable" else "enable")) }
        }) {
            Text(t("telemetryDisclosure"))
            OutlinedTextField(value = interval, onValueChange = { interval = it }, enabled = !configuration.enabled,
                label = { Text(t("interval")) }, singleLine = true)
            Text(t("changeWhenStopped"))
            Text(t("usageThrough", "time" to workerTime(status.usageQueriedThrough)))
            TextButton(onClick = {
                runCatching { openWorkerSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageSpecific = true) }
                    .onFailure { onError(it.workerMessage()) }
            }) { Text(t("usageAccess")) }
        }
        if (!configuration.enabled) Text(t("telemetryConsent"), style = MaterialTheme.typography.bodySmall)
    }
}
