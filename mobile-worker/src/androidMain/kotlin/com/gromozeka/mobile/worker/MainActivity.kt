package com.gromozeka.mobile.worker

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.gromozeka.domain.model.MobileWorkerAppState
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import java.util.UUID

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: MobileWorkerRuntime
    private var foregroundHeartbeat: Job? = null
    private var statusChanged: ((MobileWorkerStatus) -> Unit)? = null
    private var errorChanged: ((String?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = AndroidMobileWorkerRuntimeFactory.create(applicationContext)
        setContent {
            MobileWorkerApp(
                runtime = runtime,
                onStatusListener = { statusChanged = it },
                onErrorListener = { errorChanged = it },
                onEnableLocation = ::requestBackgroundLocation,
            )
        }
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V,
            null,
        )
        captureAndSynchronize()
        foregroundHeartbeat?.cancel()
        foregroundHeartbeat = activityScope.launch {
            while (isActive) {
                delay(FOREGROUND_HEARTBEAT_INTERVAL_MILLIS)
                runCatching {
                    runtime.synchronize(MobileWorkerAppState.FOREGROUND, heartbeatWhenIdle = true)
                }.onSuccess { statusChanged?.invoke(it) }
                    .onFailure { errorChanged?.invoke(it.message ?: it.toString()) }
            }
        }
    }

    override fun onPause() {
        foregroundHeartbeat?.cancel()
        foregroundHeartbeat = null
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onTagDiscovered(tag: Tag) {
        storeNfcTag(tag)
    }

    private fun storeNfcTag(tag: Tag) {
        val tagId = tag.id.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        activityScope.launch {
            runCatching {
                runtime.recordNfcTag(tagId)
                runtime.synchronize(MobileWorkerAppState.FOREGROUND)
            }.onSuccess { statusChanged?.invoke(it) }
                .onFailure { errorChanged?.invoke(it.message ?: it.toString()) }
        }
    }

    override fun onDestroy() {
        statusChanged = null
        errorChanged = null
        runtime.close()
        activityScope.cancel()
        super.onDestroy()
    }

    private fun captureAndSynchronize() {
        activityScope.launch {
            val status = runCatching { runtime.status() }.getOrNull() ?: return@launch
            statusChanged?.invoke(status)
            if (!status.enrolled) return@launch
            MobileWorkerSyncJobService.schedule(applicationContext)
            val sensors = AndroidMobileWorkerSensors(applicationContext)
            runCatching {
                sensors.battery()?.let {
                    runtime.recordBattery(it.levelPercent, it.charging, it.lowPowerMode)
                }
                runtime.recordAirplaneMode(sensors.airplaneMode())
                sensors.bluetoothEnabled()?.let { runtime.recordBluetoothPower(it) }
                AndroidAutoSignals.capture(applicationContext, runtime)
                sensors.captureConfiguredState(runtime)
                AndroidSleepSignals(applicationContext).captureLatestSession(runtime)
                sensors.enableSignificantLocationUpdates()
                sensors.enableBlePresenceUpdates()
                runtime.synchronize(MobileWorkerAppState.FOREGROUND, heartbeatWhenIdle = true)
            }.onSuccess { statusChanged?.invoke(it) }
                .onFailure { errorChanged?.invoke(it.message ?: it.toString()) }
        }
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == NfcAdapter.ACTION_TAG_DISCOVERED) {
            intent.nfcTag()?.let(::storeNfcTag)
        }
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        }
    }

    private fun Intent.nfcTag(): Tag? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

    private companion object {
        const val FOREGROUND_HEARTBEAT_INTERVAL_MILLIS = 60_000L
    }
}

@Composable
private fun MainActivity.MobileWorkerApp(
    runtime: MobileWorkerRuntime,
    onStatusListener: (((MobileWorkerStatus) -> Unit)?) -> Unit,
    onErrorListener: (((String?) -> Unit)?) -> Unit,
    onEnableLocation: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<MobileWorkerStatus?>(null) }
    var serverUrl by remember { mutableStateOf("") }
    var enrollmentToken by remember { mutableStateOf("") }
    var workerId by remember { mutableStateOf(defaultWorkerId()) }
    var connectionChallenge by remember { mutableStateOf<MobileWorkerConnectionChallenge?>(null) }
    var usePassword by remember { mutableStateOf(false) }
    var showAdvancedEnrollment by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    val sensors = remember { AndroidMobileWorkerSensors(applicationContext) }
    val configurationStore = remember { AndroidMobileWorkerConfigurationStore(applicationContext) }
    var configuration by remember { mutableStateOf(configurationStore.read()) }
    val sleepPermissionLauncher = rememberLauncherForActivityResult(
        AndroidSleepSignals.permissionContract
    ) { permissions ->
        val sleep = AndroidSleepSignals(applicationContext)
        locationMessage = if (sleep.hasSleepReadPermission(permissions)) {
            scope.launch {
                runCatching {
                    sleep.captureLatestSession(runtime)
                    runtime.synchronize(MobileWorkerAppState.FOREGROUND)
                }.onSuccess { status = it }
                    .onFailure { error = it.message ?: it.toString() }
            }
            "Sleep events are enabled"
        } else {
            "Sleep access was not granted"
        }
    }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        locationMessage = if (permissions.values.all { it } && sensors.enableBlePresenceUpdates()) {
            "BLE presence events are enabled"
        } else {
            "Bluetooth access is required for BLE presence events"
        }
    }
    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        locationMessage = if (granted && sensors.enableSignificantLocationUpdates()) {
            "Background location is active"
        } else {
            "Allow location all the time in system settings"
        }
    }
    val foregroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        when {
            !granted -> locationMessage = "Location permission was not granted"
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
                backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                onEnableLocation()
                locationMessage = "Choose Permissions, Location, Allow all the time"
            }
            sensors.enableSignificantLocationUpdates() -> locationMessage = "Background location is active"
            else -> locationMessage = "Location provider is unavailable"
        }
    }

    DisposableEffect(Unit) {
        onStatusListener { status = it }
        onErrorListener { error = it }
        onDispose {
            onStatusListener(null)
            onErrorListener(null)
        }
    }
    LaunchedEffect(Unit) {
        status = runtime.status()
    }
    LaunchedEffect(connectionChallenge) {
        val challenge = connectionChallenge ?: return@LaunchedEffect
        while (Clock.System.now() < challenge.expiresAt && status?.enrolled != true) {
            delay(challenge.pollIntervalSeconds * 1_000L)
            runCatching {
                runtime.consumeDeviceConnection(serverUrl, challenge.deviceToken)
            }.onSuccess { result ->
                when (result.status) {
                    MobileWorkerConnectionStatus.PENDING -> error = null
                    MobileWorkerConnectionStatus.CONNECTED -> {
                        status = result.workerStatus
                        connectionChallenge = null
                        MobileWorkerSyncJobService.schedule(applicationContext)
                        status = runtime.synchronize(MobileWorkerAppState.FOREGROUND)
                        return@LaunchedEffect
                    }
                    MobileWorkerConnectionStatus.DENIED,
                    MobileWorkerConnectionStatus.EXPIRED -> {
                        error = result.message ?: "Device connection ${result.status.name.lowercase()}"
                        connectionChallenge = null
                        return@LaunchedEffect
                    }
                }
            }.onFailure {
                error = "Connection interrupted. Retrying..."
            }
        }
        if (connectionChallenge == challenge) {
            error = "Connection code expired"
            connectionChallenge = null
        }
    }

    MaterialTheme(colorScheme = workerColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = workerColors.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "GROMOZEKA / MOBILE WORKER",
                    color = workerColors.primary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = "Device signals, stored first.",
                    color = workerColors.onBackground,
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    text = "This app runs independently from the chat client and only removes events after the server acknowledges them.",
                    color = workerColors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )

                status?.takeIf { it.enrolled }?.let { enrolled ->
                    StatusCard(enrolled)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    error = null
                                    runCatching {
                                        runtime.synchronize(
                                            MobileWorkerAppState.FOREGROUND,
                                            heartbeatWhenIdle = true,
                                        )
                                    }
                                        .onSuccess { status = it }
                                        .onFailure { error = it.message ?: it.toString() }
                                    busy = false
                                }
                            },
                        ) {
                            Text(if (busy) "Syncing" else "Sync now")
                        }
                        OutlinedButton(
                            onClick = {
                                foregroundPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    )
                                )
                            },
                        ) {
                            Text("Enable location")
                        }
                    }
                    locationMessage?.let { Text(it, color = workerColors.onSurfaceVariant) }
                    SignalSettings(
                        configuration = configuration,
                        sensors = sensors,
                        onAddGeofence = { id, latitude, longitude, radius ->
                            configurationStore.addGeofence(id, latitude, longitude, radius)
                                .also { configuration = it }
                            check(sensors.enableSignificantLocationUpdates()) {
                                "Allow precise location all the time to activate geofences"
                            }
                        },
                        onRemoveGeofence = { id ->
                            configuration = configurationStore.removeGeofence(id)
                            check(sensors.synchronizeGeofences()) {
                                "Geofence registration could not be updated"
                            }
                        },
                        onAddBleDevice = { name, selector ->
                            configurationStore.addBleDevice(name, selector).also { configuration = it }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                bluetoothPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH_SCAN,
                                        Manifest.permission.BLUETOOTH_CONNECT,
                                    )
                                )
                            } else if (sensors.enableBlePresenceUpdates()) {
                                locationMessage = "BLE presence events are enabled"
                            }
                        },
                        onRemoveBleDevice = { id ->
                            configuration = configurationStore.removeBleDevice(id)
                            sensors.enableBlePresenceUpdates()
                        },
                        onWifiChanged = { networkId ->
                            configurationStore.setWifiNetworkId(networkId).also { configuration = it }
                            scope.launch {
                                runCatching {
                                    sensors.captureConfiguredState(runtime)
                                    runtime.synchronize(MobileWorkerAppState.FOREGROUND)
                                }.onSuccess { status = it }
                                    .onFailure { error = it.message ?: it.toString() }
                            }
                        },
                        onEnableSleep = {
                            val sleep = AndroidSleepSignals(applicationContext)
                            val permissions = sleep.requestedPermissions()
                            if (permissions.isEmpty()) {
                                locationMessage = "Health Connect is unavailable"
                            } else {
                                sleepPermissionLauncher.launch(permissions)
                            }
                        },
                        onMessage = { locationMessage = it },
                        onError = { error = it },
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    sensors.disableBackgroundSignals()
                                    MobileWorkerSyncJobService.cancel(applicationContext)
                                    runtime.reset()
                                    configuration = configurationStore.clear()
                                    locationMessage = null
                                    runtime.status()
                                }.onSuccess { status = it }
                                    .onFailure {
                                        status = runCatching { runtime.status() }.getOrNull() ?: status
                                        error = it.message ?: it.toString()
                                    }
                            }
                        },
                    ) {
                        Text("Remove from this device")
                    }
                } ?: run {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("Server URL") },
                        placeholder = { Text("https://gromozeka.example") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = workerId,
                        onValueChange = { workerId = it },
                        label = { Text("Worker ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    connectionChallenge?.let { challenge ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = workerColors.surfaceVariant,
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("APPROVE THIS CODE", color = workerColors.primary)
                                Text(
                                    challenge.userCode,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                )
                                Text(
                                    "Open Settings > Security in an authorized Gromozeka Client.",
                                    color = workerColors.onSurfaceVariant,
                                )
                            }
                        }
                    } ?: run {
                        if (usePassword) {
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text("Username") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                enabled = !busy && serverUrl.isNotBlank() && workerId.isNotBlank() &&
                                    username.isNotBlank() && password.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = workerColors.primary),
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        error = null
                                        runCatching {
                                            val challenge = runtime.startDeviceConnection(serverUrl, workerId)
                                            val result = runtime.connectWithPassword(
                                                serverUrl,
                                                challenge.deviceToken,
                                                username,
                                                password,
                                            )
                                            status = requireNotNull(result.workerStatus)
                                            password = ""
                                            MobileWorkerSyncJobService.schedule(applicationContext)
                                            status = runtime.synchronize(MobileWorkerAppState.FOREGROUND)
                                        }.onFailure { failure ->
                                            error = failure.message ?: failure.toString()
                                        }
                                        busy = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (busy) "Connecting" else "Connect with password")
                            }
                        } else {
                            Button(
                                enabled = !busy && serverUrl.isNotBlank() && workerId.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = workerColors.primary),
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        error = null
                                        runCatching { runtime.startDeviceConnection(serverUrl, workerId) }
                                            .onSuccess { connectionChallenge = it }
                                            .onFailure { error = it.message ?: it.toString() }
                                        busy = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (busy) "Creating code" else "Connect device")
                            }
                        }
                        TextButton(onClick = { usePassword = !usePassword }) {
                            Text(if (usePassword) "Use connection code" else "Use username and password")
                        }
                    }

                    TextButton(onClick = { showAdvancedEnrollment = !showAdvancedEnrollment }) {
                        Text(if (showAdvancedEnrollment) "Hide advanced enrollment" else "Advanced")
                    }
                    if (showAdvancedEnrollment) {
                        OutlinedTextField(
                            value = enrollmentToken,
                            onValueChange = { enrollmentToken = it },
                            label = { Text("One-time enrollment token") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            enabled = !busy && serverUrl.isNotBlank() &&
                                enrollmentToken.isNotBlank() && workerId.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    busy = true
                                    error = null
                                    runCatching {
                                        status = runtime.enroll(serverUrl, enrollmentToken, workerId)
                                        enrollmentToken = ""
                                        MobileWorkerSyncJobService.schedule(applicationContext)
                                        status = runtime.synchronize(MobileWorkerAppState.FOREGROUND)
                                    }.onFailure { failure ->
                                        error = failure.message ?: failure.toString()
                                    }
                                    busy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Use one-time token")
                        }
                    }
                }

                error?.let {
                    Text(it, color = workerColors.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SignalSettings(
    configuration: AndroidMobileWorkerConfiguration,
    sensors: AndroidMobileWorkerSensors,
    onAddGeofence: (String, Double, Double, Double) -> Unit,
    onRemoveGeofence: (String) -> Unit,
    onAddBleDevice: (String?, String) -> Unit,
    onRemoveBleDevice: (String) -> Unit,
    onWifiChanged: (String?) -> Unit,
    onEnableSleep: () -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    var geofenceId by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("250") }
    var bleName by remember { mutableStateOf("") }
    var bleSelector by remember { mutableStateOf("") }
    var wifiNetworkId by remember(configuration.wifiNetworkId) {
        mutableStateOf(configuration.wifiNetworkId.orEmpty())
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("DEVICE SIGNALS", color = workerColors.primary, fontFamily = FontFamily.Monospace)
        Text(
            "Only configured geofences, BLE devices and Wi-Fi networks are monitored.",
            color = workerColors.onSurfaceVariant,
        )

        Text("Geofences", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = geofenceId,
                onValueChange = { geofenceId = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    val current = sensors.lastKnownLocation()
                    if (current == null) {
                        onMessage("No recent location is available yet")
                    } else {
                        latitude = current.latitude.toString()
                        longitude = current.longitude.toString()
                    }
                },
            ) {
                Text("Use current")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = latitude,
                onValueChange = { latitude = it },
                label = { Text("Latitude") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = longitude,
                onValueChange = { longitude = it },
                label = { Text("Longitude") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = radius,
                onValueChange = { radius = it },
                label = { Text("Radius, m") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    runCatching {
                        onAddGeofence(
                            geofenceId,
                            latitude.toDouble(),
                            longitude.toDouble(),
                            radius.toDouble(),
                        )
                    }.onSuccess {
                        geofenceId = ""
                        onMessage("Geofence saved")
                    }.onFailure { onError(it.message ?: it.toString()) }
                },
            ) {
                Text("Add")
            }
        }
        configuration.geofences.forEach { geofence ->
            ConfiguredSignalRow(
                title = geofence.id,
                detail = "${geofence.latitude}, ${geofence.longitude} / ${geofence.radiusMeters.toInt()} m",
                onRemove = { onRemoveGeofence(geofence.id) },
            )
        }

        Text("BLE devices", fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = bleName,
            onValueChange = { bleName = it },
            label = { Text("Display name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = bleSelector,
            onValueChange = { bleSelector = it },
            label = { Text("MAC address or service UUID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = bleSelector.isNotBlank(),
            onClick = {
                runCatching { onAddBleDevice(bleName, bleSelector) }
                    .onSuccess {
                        bleName = ""
                        bleSelector = ""
                    }
                    .onFailure { onError(it.message ?: it.toString()) }
            },
        ) {
            Text("Add BLE device")
        }
        configuration.bleDevices.forEach { device ->
            ConfiguredSignalRow(
                title = device.displayName ?: device.id,
                detail = device.address ?: device.serviceUuid.orEmpty(),
                onRemove = { onRemoveBleDevice(device.id) },
            )
        }

        Text("Wi-Fi", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = wifiNetworkId,
                onValueChange = { wifiNetworkId = it },
                label = { Text("Selected network name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onWifiChanged(wifiNetworkId) }) {
                Text("Save")
            }
        }
        configuration.wifiNetworkId?.let { selected ->
            TextButton(onClick = {
                wifiNetworkId = ""
                onWifiChanged(null)
            }) {
                Text("Stop monitoring $selected")
            }
        }

        OutlinedButton(onClick = onEnableSleep) {
            Text("Enable sleep events")
        }
    }
}

@Composable
private fun ConfiguredSignalRow(
    title: String,
    detail: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, color = workerColors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onRemove) {
            Text("Remove")
        }
    }
}

@Composable
private fun StatusCard(status: MobileWorkerStatus) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(workerColors.surface, RoundedCornerShape(18.dp))
            .padding(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ENROLLED", color = workerColors.primary, fontFamily = FontFamily.Monospace)
            Text(status.workerId.orEmpty(), fontWeight = FontWeight.Bold)
            Text(status.serverUrl.orEmpty(), color = workerColors.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(
                "Pending events: ${status.pendingEventCount}",
                color = if (status.pendingEventCount == 0) workerColors.secondary else workerColors.tertiary,
            )
            Text(
                status.lastSynchronizedAt?.let { "Last sync: $it" } ?: "Not synchronized yet",
                color = workerColors.onSurfaceVariant,
            )
        }
    }
}

private fun MainActivity.defaultWorkerId(): String {
    val preferences = getSharedPreferences(
        "gromozeka-mobile-worker-identity",
        android.content.Context.MODE_PRIVATE,
    )
    val suffix = preferences.getString("installation-id", null) ?: UUID.randomUUID()
        .toString()
        .replace("-", "")
        .take(8)
        .also { installationId ->
            check(preferences.edit().putString("installation-id", installationId).commit()) {
                "Mobile Worker installation ID could not be persisted"
            }
        }
    return "android-${Build.MODEL}-$suffix"
        .lowercase()
        .replace(Regex("[^a-z0-9._-]"), "-")
        .take(64)
}

private val workerColors = darkColorScheme(
    primary = Color(0xFFEF9F3B),
    onPrimary = Color(0xFF17110A),
    secondary = Color(0xFF72D6A2),
    tertiary = Color(0xFFFFD07A),
    background = Color(0xFF101714),
    onBackground = Color(0xFFF2F0E8),
    surface = Color(0xFF1A2420),
    onSurface = Color(0xFFF2F0E8),
    onSurfaceVariant = Color(0xFFAAB8B0),
    error = Color(0xFFFF7B72),
)
