package com.gromozeka.mobile.worker

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gromozeka.worker.runtime.WorkerLocationConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun MainActivity.WorkerLocationSettings(
    runtime: MobileWorkerRuntime,
    status: MobileWorkerStatus,
    onStatus: (MobileWorkerStatus) -> Unit,
    onError: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val source = remember { AndroidWorkerLocationSource(applicationContext) }
    var permission by remember { mutableStateOf(source.hasPermission()) }
    var backgroundPermission by remember { mutableStateOf(source.hasBackgroundPermission()) }
    val enabled = status.locationConfiguration.enabled
    var interval by remember(status.locationConfiguration.intervalSeconds) { mutableStateOf(status.locationConfiguration.intervalSeconds.toString()) }
    var distance by remember(status.locationConfiguration.minimumDistanceMeters) { mutableStateOf(status.locationConfiguration.minimumDistanceMeters.toString()) }
    val state by AndroidWorkerLocationService.state.collectAsState()
    val delivery by AndroidWorkerLocationService.delivery.collectAsState()
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            permission = source.hasPermission()
            backgroundPermission = source.hasBackgroundPermission()
            onStatus(runtime.status())
            delay(2_000)
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permission = source.hasPermission()
        if (!permission) onError("Location permission was not granted. Sharing remains under your control.")
    }
    val backgroundLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        backgroundPermission = source.hasBackgroundPermission()
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it) onError("Allow notifications to keep location sharing visible, then enable sharing.")
    }
    Text("Location sharing: ${if (enabled) "enabled" else "disabled"}", style = MaterialTheme.typography.titleMedium)
    Text("Share this device's positions with your server, including while the screen is off. Works independently of remote commands. Points are stored here while offline and sent when connected. Previously recorded points remain in history after disabling sharing.")
    if (enabled) {
        Text(state)
        if (delivery.isNotBlank()) Text(delivery)
        status.lastLocation?.let { Text("Last recorded: ${it.observedAt} · accuracy ${it.location.accuracyMeters ?: "unknown"} m") }
    }
    Text(if (permission) "Android location permission granted (precise or approximate)" else "Android location permission is missing")
    TextButton(onClick = {
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }) { Text("Location permission") }
    TextButton(onClick = {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }) { Text("Android app permissions") }
    if (!backgroundPermission) {
        Text("Optional: allow location all the time to resume after reboot without opening the app. On Android 11+, choose Permissions → Location → Allow all the time. You can decline and start sharing by opening the Worker.")
        TextButton(enabled = permission, onClick = {
            if (Build.VERSION.SDK_INT == 29) backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }) { Text("Background location permission") }
    } else Text("Background location permission granted")
    OutlinedTextField(value = interval, onValueChange = { interval = it }, enabled = !enabled, label = { Text("Minimum interval (seconds)") })
    OutlinedTextField(value = distance, onValueChange = { distance = it }, enabled = !enabled, label = { Text("Minimum movement (meters)") })
    Text("Defaults: 60 seconds and 25 meters; both thresholds apply. GPS, indoor reception and Android can delay fixes. Short intervals use more battery. Disable sharing to change these settings.")
    OutlinedButton(onClick = {
        scope.launch {
            runCatching {
                if (enabled) {
                    runtime.configureLocation(status.locationConfiguration.copy(enabled = false))
                    AndroidWorkerLocationService.stop(applicationContext)
                } else {
                    require(source.hasPermission()) { "Grant location permission first" }
                    source.provider()
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        val configuration = WorkerLocationConfiguration(true,
                            requireNotNull(interval.toIntOrNull()) { "Interval must be a whole number of seconds" },
                            requireNotNull(distance.toIntOrNull()) { "Movement must be a whole number of meters" })
                        runtime.configureLocation(configuration)
                        AndroidWorkerLocationService.start(applicationContext)
                    }
                }
                onStatus(runtime.status())
                onError(null)
            }.onFailure { onError(it.message ?: "Location sharing could not be changed") }
        }
    }) { Text(if (enabled) "Disable location sharing" else "Enable location sharing") }
}
