package com.gromozeka.mobile.worker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gromozeka.worker.runtime.WorkerLocationConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MainActivity.WorkerLocationSettings(
    runtime: MobileWorkerRuntime,
    status: MobileWorkerStatus,
    onStatus: (MobileWorkerStatus) -> Unit,
    onError: (WorkerMessage?) -> Unit,
) {
    val t = rememberWorkerStrings()
    val scope = rememberCoroutineScope()
    val source = remember { AndroidWorkerLocationSource(applicationContext) }
    var permission by remember { mutableStateOf(source.hasPermission()) }
    var backgroundPermission by remember { mutableStateOf(source.hasBackgroundPermission()) }
    val enabled = status.locationConfiguration.enabled
    var interval by remember(status.locationConfiguration.intervalSeconds) { mutableStateOf(status.locationConfiguration.intervalSeconds.toString()) }
    var distance by remember(status.locationConfiguration.minimumDistanceMeters) { mutableStateOf(status.locationConfiguration.minimumDistanceMeters.toString()) }
    var busy by remember { mutableStateOf(false) }
    val state by AndroidWorkerLocationService.state.collectAsState()
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed) {
        if (resumed) while (true) {
            permission = source.hasPermission()
            backgroundPermission = source.hasBackgroundPermission()
            runCatching { withContext(Dispatchers.IO) { runtime.status() } }.onSuccess(onStatus)
            delay(2_000)
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permission = source.hasPermission()
        if (!permission) onError(WorkerMessage("locationRequired"))
    }
    val backgroundLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        backgroundPermission = source.hasBackgroundPermission()
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onError(WorkerMessage(if (it) "permissionSaved" else "notificationsRequired"))
    }
    WorkerCard(t("location")) {
        Text(t(if (!enabled) "off" else if (!permission) "locationRequired" else state.key))
        if (status.lastLocation != null) Text(t("lastCollection", "time" to workerTime(status.lastLocation?.observedAt)), style = MaterialTheme.typography.bodySmall)
        status.lastLocation?.location?.accuracyMeters?.let { accuracy ->
            Text(t("accuracy", "meters" to java.text.NumberFormat.getNumberInstance(resources.configuration.locales[0]).format(accuracy)))
        }
        if (!permission) TextButton(onClick = {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }) { Text(t("locationAccess")) }
        WorkerDetails(leadingAction = {
            OutlinedButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        if (enabled) {
                            withContext(Dispatchers.IO) { runtime.configureLocation(status.locationConfiguration.copy(enabled = false)) }
                            AndroidWorkerLocationService.stop(applicationContext)
                        } else {
                            workerRequire(source.hasPermission(), "locationRequired")
                            workerRequire(runCatching { source.provider() }.isSuccess, "locationProviderRequired")
                            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                return@launch
                            }
                            workerRequire(AndroidWorkerLocationService.notificationsAllowed(applicationContext), "notificationsRequired")
                            val seconds = interval.toIntOrNull()
                            val meters = distance.toIntOrNull()
                            workerRequire(seconds != null && meters != null, "invalidLocationSettings")
                            val configuration = try { WorkerLocationConfiguration(true, seconds!!, meters!!) }
                            catch (_: IllegalArgumentException) { throw WorkerUiException("invalidLocationSettings") }
                            withContext(Dispatchers.IO) { runtime.configureLocation(configuration) }
                            AndroidWorkerLocationService.start(applicationContext)
                        }
                        onStatus(withContext(Dispatchers.IO) { runtime.status() })
                        onError(null)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { onError(e.workerMessage()) }
                    finally { busy = false }
                }
            }) { Text(t(if (busy) "working" else if (enabled) "disable" else "enable")) }
        }) {
            Text(t("locationDisclosure"))
            Text(t("backgroundLocation", "status" to t(if (backgroundPermission) "allowed" else "notAllowed")))
            Text(t("backgroundLocationHint"))
            TextButton(onClick = {
                runCatching {
                    if (Build.VERSION.SDK_INT == 29 && permission && !backgroundPermission)
                        backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    else openWorkerSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageSpecific = true)
                }.onFailure { onError(it.workerMessage()) }
            }) { Text(t("appPermissions")) }
            TextButton(onClick = {
                runCatching { openWorkerSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }
                    .onFailure { onError(it.workerMessage()) }
            }) { Text(t("locationAccess")) }
            OutlinedTextField(value = interval, onValueChange = { interval = it }, enabled = !enabled,
                label = { Text(t("interval")) }, singleLine = true)
            OutlinedTextField(value = distance, onValueChange = { distance = it }, enabled = !enabled,
                label = { Text(t("distance")) }, singleLine = true)
            Text(t("changeWhenStopped"))
        }
        if (!enabled) Text(t("locationConsent"), style = MaterialTheme.typography.bodySmall)
    }
}
