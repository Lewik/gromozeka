package com.gromozeka.mobile.worker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.gromozeka.domain.model.DeviceCollectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MainActivity.WorkerTelemetrySettings(
    runtime: MobileWorkerRuntime,
    status: MobileWorkerStatus,
    onStatus: (MobileWorkerStatus) -> Unit,
    onError: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val configuration = status.telemetryConfiguration
    var usage by remember(configuration.applicationUsageEnabled) { mutableStateOf(configuration.applicationUsageEnabled) }
    var interval by remember(configuration.intervalSeconds) { mutableStateOf(configuration.intervalSeconds.toString()) }
    val state by AndroidWorkerTelemetryService.state.collectAsState()
    val delivery by AndroidWorkerEventDelivery.state.collectAsState()
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it) onError(getString(R.string.telemetry_notifications_required))
    }
    Text(stringResource(R.string.telemetry_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.telemetry_disclosure))
    Row {
        Checkbox(checked = usage, onCheckedChange = { usage = it }, enabled = !configuration.enabled)
        Text(stringResource(R.string.telemetry_app_usage))
    }
    TextButton(onClick = {
        runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))) }
            .onFailure { onError(getString(R.string.telemetry_usage_required)) }
    }) { Text(stringResource(R.string.telemetry_usage_access)) }
    OutlinedTextField(value = interval, onValueChange = { interval = it }, enabled = !configuration.enabled,
        label = { Text(stringResource(R.string.telemetry_interval)) })
    if (configuration.enabled) {
        if (state.isNotBlank()) Text(state)
        if (delivery.isNotBlank()) Text(delivery)
        Text(stringResource(R.string.telemetry_last_collection, status.lastTelemetryCollectedAt?.toString() ?: "—"))
        if (configuration.applicationUsageEnabled) {
            Text(stringResource(R.string.telemetry_usage_through, status.usageQueriedThrough?.toString() ?: "—"))
        }
    }
    OutlinedButton(onClick = {
        scope.launch {
            runCatching {
                if (configuration.enabled) {
                    withContext(Dispatchers.IO) { runtime.configureTelemetry(configuration.copy(enabled = false)) }
                    AndroidWorkerTelemetryService.stop(applicationContext)
                } else {
                    require(!usage || AndroidWorkerTelemetrySource(applicationContext).usageAccess() == DeviceCollectionState.ACTIVE) {
                        getString(R.string.telemetry_usage_required)
                    }
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        return@runCatching
                    }
                    require(AndroidWorkerTelemetryService.notificationsAllowed(applicationContext)) {
                        getString(R.string.telemetry_notifications_required)
                    }
                    val seconds = requireNotNull(interval.toIntOrNull()) { getString(R.string.telemetry_invalid_interval) }
                    require(seconds in 10..900) { getString(R.string.telemetry_invalid_interval) }
                    withContext(Dispatchers.IO) { runtime.configureTelemetry(WorkerTelemetryConfiguration(true, usage, seconds)) }
                    AndroidWorkerTelemetryService.start(applicationContext)
                }
                onStatus(withContext(Dispatchers.IO) { runtime.status() })
                onError(null)
            }.onFailure { onError(it.message ?: getString(R.string.telemetry_save_failed)) }
        }
    }) { Text(stringResource(if (configuration.enabled) R.string.telemetry_disable else R.string.telemetry_enable)) }
    Text(stringResource(R.string.telemetry_limitations))
}
