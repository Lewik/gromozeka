package com.gromozeka.mobile.worker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.gromozeka.domain.model.WorkerAppState
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.safeDrawingPadding
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
import kotlin.time.Clock
import java.util.UUID

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: MobileWorkerRuntime
    private var foregroundHeartbeat: Job? = null
    private var statusChanged: ((MobileWorkerStatus) -> Unit)? = null
    private var errorChanged: ((WorkerMessage?) -> Unit)? = null
    private var permissionRevision by mutableStateOf(0)
    private var backgroundAccess by mutableStateOf(AndroidWorkerBackgroundAccess(false, false))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = AndroidMobileWorkerRuntimeFactory.create(applicationContext)
        setContent {
            MobileWorkerApp(
                runtime = runtime,
                backgroundAccess = backgroundAccess,
                permissionRevision = permissionRevision,
                onStatusListener = { statusChanged = it },
                onErrorListener = { errorChanged = it },
            )
        }
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        permissionRevision++
        backgroundAccess = AndroidWorkerBackgroundAccess.read(applicationContext)
        activityScope.launch {
            runCatching { AndroidWorkerSoundOutput.recoverVolume(applicationContext) }
                .onFailure { errorChanged?.invoke(it.workerMessage()) }
        }
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
                    runtime.synchronize(WorkerAppState.FOREGROUND, heartbeatWhenIdle = true)
                }.onSuccess { statusChanged?.invoke(it) }
                    .onFailure { androidMobileWorkerLog.warn { "Foreground heartbeat failed: ${it.javaClass.simpleName}" } }
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
                runtime.synchronize(WorkerAppState.FOREGROUND)
            }.onSuccess { statusChanged?.invoke(it) }
                .onFailure { errorChanged?.invoke(it.workerMessage()) }
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
            if (status.gatewayEnabled) {
                runCatching { AndroidWorkerGatewayService.start(applicationContext) }
                    .onFailure { errorChanged?.invoke(it.workerMessage()) }
            }
            if (status.locationConfiguration.enabled) {
                runCatching { AndroidWorkerLocationService.start(applicationContext) }
                    .onFailure { errorChanged?.invoke(it.workerMessage()) }
            }
            if (status.telemetryConfiguration.enabled) {
                runCatching { AndroidWorkerTelemetryService.start(applicationContext) }
                    .onFailure { errorChanged?.invoke(it.workerMessage()) }
            }
            MobileWorkerSyncJobService.schedule(applicationContext)
            val sensors = AndroidMobileWorkerSensors(applicationContext)
            val observations: List<suspend () -> Unit> = listOf(
                { sensors.battery()?.let { runtime.recordBattery(it.levelPercent, it.charging, it.lowPowerMode) } },
                { runtime.recordAirplaneMode(sensors.airplaneMode()) },
                { sensors.bluetoothEnabled()?.let { runtime.recordBluetoothPower(it) } },
                { AndroidAutoSignals.capture(applicationContext, runtime) },
                { sensors.captureConfiguredState(runtime) },
                { AndroidSleepSignals(applicationContext).captureLatestSession(runtime) },
                { sensors.synchronizeGeofences() },
                { sensors.enableBlePresenceUpdates() },
            )
            observations.forEach { collect ->
                try { collect() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { androidMobileWorkerLog.warn { "Optional foreground signal failed: ${e.javaClass.simpleName}" } }
            }
            runCatching {
                runtime.synchronize(WorkerAppState.FOREGROUND, heartbeatWhenIdle = true)
            }.onSuccess { statusChanged?.invoke(it) }
                .onFailure { androidMobileWorkerLog.warn { "Foreground delivery failed: ${it.javaClass.simpleName}" } }
        }
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == NfcAdapter.ACTION_TAG_DISCOVERED) {
            intent.nfcTag()?.let(::storeNfcTag)
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
    backgroundAccess: AndroidWorkerBackgroundAccess,
    permissionRevision: Int,
    onStatusListener: (((MobileWorkerStatus) -> Unit)?) -> Unit,
    onErrorListener: (((WorkerMessage?) -> Unit)?) -> Unit,
) {
    val t = rememberWorkerStrings()
    val notificationsAllowed = remember(permissionRevision) { getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() }
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
    var error by remember { mutableStateOf<WorkerMessage?>(null) }
    var locationMessage by remember { mutableStateOf<WorkerMessage?>(null) }
    var removeConfirmation by remember { mutableStateOf(false) }
    val gatewayState by AndroidWorkerGatewayService.state.collectAsState()
    val soundPlaying by AndroidWorkerGatewayService.soundPlaying.collectAsState()
    val soundError by AndroidWorkerGatewayService.soundError.collectAsState()
    LaunchedEffect(gatewayState) {
        runCatching { runtime.status() }.onSuccess { status = it }
    }
    val enableGateway: () -> Unit = {
        scope.launch {
            runCatching {
                runtime.setGatewayEnabled(true)
                AndroidWorkerGatewayService.start(applicationContext)
                runtime.status()
            }.onSuccess { status = it }
                .onFailure { error = it.workerMessage() }
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableGateway()
        else error = WorkerMessage("notificationsRequired")
    }
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
                    runtime.synchronize(WorkerAppState.FOREGROUND)
                }.onSuccess { status = it }
                    .onFailure { error = it.workerMessage() }
            }
            WorkerMessage("sleepEnabled")
        } else {
            WorkerMessage("sleepRequired")
        }
    }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        locationMessage = if (permissions.values.all { it } && sensors.enableBlePresenceUpdates()) {
            WorkerMessage("bleEnabled")
        } else {
            WorkerMessage("bleRequired")
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
                        status = runtime.synchronize(WorkerAppState.FOREGROUND)
                        return@LaunchedEffect
                    }
                    MobileWorkerConnectionStatus.DENIED,
                    MobileWorkerConnectionStatus.EXPIRED -> {
                        error = WorkerMessage(if (result.status == MobileWorkerConnectionStatus.EXPIRED) "codeExpired" else "connectionDenied")
                        connectionChallenge = null
                        return@LaunchedEffect
                    }
                }
            }.onFailure {
                error = WorkerMessage("retrying")
            }
        }
        if (connectionChallenge == challenge) {
            error = WorkerMessage("codeExpired")
            connectionChallenge = null
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides if (t.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        MaterialTheme(colorScheme = workerColors) {
            WorkerMessageDialog(error ?: locationMessage) { error = null; locationMessage = null }
            Surface(modifier = Modifier.fillMaxSize(), color = workerColors.background) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Gromozeka Worker", style = MaterialTheme.typography.headlineSmall, color = workerColors.primary)

                    status?.takeIf { it.enrolled }?.let { enrolled ->
                        StatusCard(enrolled) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    enabled = !busy,
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            error = null
                                            runCatching {
                                                runtime.synchronize(
                                                    WorkerAppState.FOREGROUND,
                                                    heartbeatWhenIdle = true,
                                                )
                                            }
                                                .onSuccess { status = it }
                                                .onFailure { error = it.workerMessage() }
                                            busy = false
                                        }
                                    },
                                ) {
                                    Text(if (busy) t("working") else t("sync"))
                                }
                            }
                        }
                        WorkerTelemetrySettings(runtime, enrolled, onStatus = { status = it }, onError = { error = it })
                        WorkerLocationSettings(runtime, enrolled, onStatus = { status = it }, onError = { error = it })
                        WorkerCard(t("background")) {
                            if (!notificationsAllowed) {
                                Text(t("notificationsRequired"), color = workerColors.error)
                                TextButton(onClick = {
                                    runCatching { openWorkerSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS) { putExtra(Settings.EXTRA_APP_PACKAGE, packageName) } }
                                        .onFailure { error = it.workerMessage() }
                                }) { Text(t("appPermissions")) }
                            }
                            Text(when {
                                backgroundAccess.backgroundRestricted -> t("backgroundRestricted")
                                backgroundAccess.batteryOptimizationExempt -> t("backgroundAllowed")
                                else -> t("backgroundOptimized")
                            }, fontWeight = FontWeight.Bold)
                            TextButton(onClick = {
                                runCatching { openWorkerSettings(backgroundAccess.settingsIntent(packageName).action!!, packageSpecific = true) }
                                    .onFailure { error = WorkerMessage("settingsUnavailable") }
                            }) {
                                Text(if (backgroundAccess.backgroundRestricted || backgroundAccess.batteryOptimizationExempt)
                                    t("batterySettings") else t("batterySettings"))
                            }
                        }
                        WorkerCard(t("remote")) {
                            Text(t(when (gatewayState) { MobileWorkerGatewayState.CONNECTED -> "active"; MobileWorkerGatewayState.FAILED -> "failed"; MobileWorkerGatewayState.STOPPED -> "off"; else -> "starting" }))
                            if (soundPlaying) Button(onClick = { AndroidWorkerGatewayService.stopSound(applicationContext) }) { Text(t("stopSound")) }
                            WorkerDetails(t("remoteSettings")) {
                                Text(t("remoteDisclosure"),
                                    color = workerColors.onSurfaceVariant)
                                OutlinedButton(onClick = {
                                    if (enrolled.gatewayEnabled) {
                                        scope.launch {
                                            runCatching {
                                                runtime.setGatewayEnabled(false)
                                                AndroidWorkerGatewayService.stop(applicationContext)
                                                runtime.status()
                                            }.onSuccess { status = it }.onFailure { error = it.workerMessage() }
                                        }
                                    } else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else enableGateway()
                                }) { Text(if (enrolled.gatewayEnabled) t("disable") else t("enable")) }
                                Text(t("soundStatus", "status" to t(if (enrolled.soundEnabled) "allowed" else "off")), fontWeight = FontWeight.Bold)
                                Text(t("soundDisclosure"),
                                    color = workerColors.onSurfaceVariant)
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        runCatching {
                                            runtime.setSoundEnabled(!enrolled.soundEnabled)
                                            if (enrolled.soundEnabled) AndroidWorkerGatewayService.stopSound(applicationContext)
                                            runtime.status()
                                        }.onSuccess { status = it }.onFailure { error = it.workerMessage() }
                                    }
                                }) { Text(if (enrolled.soundEnabled) t("disable") else t("enable")) }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(enabled = enrolled.gatewayEnabled && enrolled.soundEnabled && !soundPlaying,
                                        onClick = { AndroidWorkerGatewayService.testSound(applicationContext) }) { Text(t("testSound")) }
                                    if (soundPlaying) Button(onClick = { AndroidWorkerGatewayService.stopSound(applicationContext) }) { Text(t("stopSound")) }
                                }
                                soundError?.let { Text(t(it.key), color = workerColors.error) }
                                TextButton(onClick = {
                                    runCatching { openWorkerSettings(Settings.ACTION_SOUND_SETTINGS) }
                                        .onFailure { error = WorkerMessage("settingsUnavailable") }
                                }) { Text(t("soundSettings")) }
                            }
                        }
                        WorkerCard(t("signals")) {
                            WorkerDetails {
                                SignalSettings(
                                    configuration = configuration,
                                    sensors = sensors,
                                    onAddGeofence = { id, latitude, longitude, radius ->
                                        configurationStore.addGeofence(id, latitude, longitude, radius)
                                            .also { configuration = it }
                                        workerRequire(sensors.synchronizeGeofences(), "geofenceRequired")
                                    },
                                    onRemoveGeofence = { id ->
                                        configuration = configurationStore.removeGeofence(id)
                                        workerRequire(sensors.synchronizeGeofences(), "geofenceRequired")
                                    },
                                    onAddBleDevice = { name, selector ->
                                        configurationStore.addBleDevice(name, selector).also { configuration = it }
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            bluetoothPermissionLauncher.launch(
                                                arrayOf(
                                                    Manifest.permission.BLUETOOTH_SCAN,
                                                    Manifest.permission.BLUETOOTH_CONNECT,
                                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                                )
                                            )
                                        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                            bluetoothPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                        } else {
                                            locationMessage = WorkerMessage(if (sensors.enableBlePresenceUpdates()) "bleEnabled" else "bleRequired")
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
                                                runtime.synchronize(WorkerAppState.FOREGROUND)
                                            }.onSuccess { status = it }
                                                .onFailure { error = it.workerMessage() }
                                        }
                                    },
                                    onEnableSleep = {
                                        val sleep = AndroidSleepSignals(applicationContext)
                                        val permissions = sleep.requestedPermissions()
                                        if (permissions.isEmpty()) {
                                            locationMessage = WorkerMessage("healthUnavailable")
                                        } else {
                                            sleepPermissionLauncher.launch(permissions)
                                        }
                                    },
                                    onMessage = { locationMessage = it },
                                    onError = { error = it },
                                )
                            }
                        }
                        TextButton(onClick = { removeConfirmation = true }) { Text(t("disconnect")) }
                        if (removeConfirmation) AlertDialog(
                            onDismissRequest = { removeConfirmation = false },
                            title = { Text(t("disconnect")) },
                            text = { Text(t("disconnectConfirm")) },
                            dismissButton = { TextButton(onClick = { removeConfirmation = false }) { Text(t("cancel")) } },
                            confirmButton = {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        removeConfirmation = false
                                        runtime.setGatewayEnabled(false)
                                        AndroidWorkerGatewayService.stop(applicationContext)
                                        runtime.configureLocation(enrolled.locationConfiguration.copy(enabled = false))
                                        AndroidWorkerLocationService.stop(applicationContext)
                                        runtime.configureTelemetry(enrolled.telemetryConfiguration.copy(enabled = false))
                                        AndroidWorkerTelemetryService.stop(applicationContext)
                                        sensors.disableBackgroundSignals()
                                        MobileWorkerSyncJobService.cancel(applicationContext)
                                        runtime.reset()
                                        configuration = configurationStore.clear()
                                        locationMessage = null
                                        runtime.status()
                                    }.onSuccess { status = it }
                                        .onFailure {
                                            status = runCatching { runtime.status() }.getOrNull() ?: status
                                            error = it.workerMessage()
                                        }
                                }
                            },
                        ) {
                            Text(t("disconnect"))
                        }
                            },
                        )
                    } ?: run {
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text(t("serverUrl")) },
                            placeholder = { Text("https://gromozeka.example") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = workerId,
                            onValueChange = { workerId = it },
                            label = { Text(t("workerId")) },
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
                                    Text(t("approveCode"), color = workerColors.primary)
                                    Text(
                                        challenge.userCode,
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                    )
                                    Text(
                                        t("approveHint"),
                                        color = workerColors.onSurfaceVariant,
                                    )
                                }
                            }
                        } ?: run {
                            if (usePassword) {
                                OutlinedTextField(
                                    value = username,
                                    onValueChange = { username = it },
                                    label = { Text(t("username")) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text(t("password")) },
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
                                                status = runtime.synchronize(WorkerAppState.FOREGROUND)
                                            }.onFailure { failure ->
                                                error = failure.workerMessage()
                                            }
                                            busy = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(if (busy) t("working") else t("connect"))
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
                                                .onFailure { error = it.workerMessage() }
                                            busy = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(if (busy) t("working") else t("connect"))
                                }
                            }
                            TextButton(onClick = { usePassword = !usePassword }) {
                                Text(if (usePassword) t("useCode") else t("usePassword"))
                            }
                        }

                        TextButton(onClick = { showAdvancedEnrollment = !showAdvancedEnrollment }) {
                            Text(if (showAdvancedEnrollment) t("advanced") else t("advanced"))
                        }
                        if (showAdvancedEnrollment) {
                            OutlinedTextField(
                                value = enrollmentToken,
                                onValueChange = { enrollmentToken = it },
                                label = { Text(t("enrollmentToken")) },
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
                                            status = runtime.synchronize(WorkerAppState.FOREGROUND)
                                        }.onFailure { failure ->
                                            error = failure.workerMessage()
                                        }
                                        busy = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(t("connect"))
                            }
                        }
                    }

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
    onMessage: (WorkerMessage) -> Unit,
    onError: (WorkerMessage) -> Unit,
) {
    val t = rememberWorkerStrings()
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
        Text(t("signals"), color = workerColors.primary, fontFamily = FontFamily.Monospace)
        Text(
            t("signalsDisclosure"),
            color = workerColors.onSurfaceVariant,
        )

        Text(t("geofences"), fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = geofenceId,
                onValueChange = { geofenceId = it },
                label = { Text(t("name")) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    val current = sensors.lastKnownLocation()
                    if (current == null) {
                        onMessage(WorkerMessage("noLocation"))
                    } else {
                        latitude = current.latitude.toString()
                        longitude = current.longitude.toString()
                    }
                },
            ) {
                Text(t("useCurrent"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = latitude,
                onValueChange = { latitude = it },
                label = { Text(t("latitude")) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = longitude,
                onValueChange = { longitude = it },
                label = { Text(t("longitude")) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = radius,
                onValueChange = { radius = it },
                label = { Text(t("radius")) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    runCatching {
                        onAddGeofence(
                            geofenceId,
                            latitude.replace(',', '.').toDouble(),
                            longitude.replace(',', '.').toDouble(),
                            radius.replace(',', '.').toDouble(),
                        )
                    }.onSuccess {
                        geofenceId = ""
                        onMessage(WorkerMessage("saved"))
                    }.onFailure { onError(it.workerMessage()) }
                },
            ) {
                Text(t("add"))
            }
        }
        configuration.geofences.forEach { geofence ->
            ConfiguredSignalRow(
                title = geofence.id,
                detail = "${geofence.latitude}, ${geofence.longitude} / ${t("radius")}: ${geofence.radiusMeters.toInt()}",
                onRemove = { runCatching { onRemoveGeofence(geofence.id) }.onFailure { onError(it.workerMessage()) } },
            )
        }

        Text(t("bleDevices"), fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = bleName,
            onValueChange = { bleName = it },
            label = { Text(t("optionalName")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = bleSelector,
            onValueChange = { bleSelector = it },
            label = { Text(t("bleSelector")) },
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
                    .onFailure { onError(it.workerMessage()) }
            },
        ) {
            Text(t("add"))
        }
        configuration.bleDevices.forEach { device ->
            ConfiguredSignalRow(
                title = device.displayName ?: device.id,
                detail = device.address ?: device.serviceUuid.orEmpty(),
                onRemove = { runCatching { onRemoveBleDevice(device.id) }.onFailure { onError(it.workerMessage()) } },
            )
        }

        Text(t("wifi"), fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = wifiNetworkId,
                onValueChange = { wifiNetworkId = it },
                label = { Text(t("wifiNetwork")) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { runCatching { onWifiChanged(wifiNetworkId) }.onFailure { onError(it.workerMessage()) } }) {
                Text(t("save"))
            }
        }
        configuration.wifiNetworkId?.let { selected ->
            TextButton(onClick = {
                wifiNetworkId = ""
                runCatching { onWifiChanged(null) }.onFailure { onError(it.workerMessage()) }
            }) {
                Text(t("stopMonitoring", "name" to selected))
            }
        }

        OutlinedButton(onClick = { runCatching(onEnableSleep).onFailure { onError(it.workerMessage()) } }) {
            Text(t("enableSleep"))
        }
    }
}

@Composable
private fun ConfiguredSignalRow(
    title: String,
    detail: String,
    onRemove: () -> Unit,
) {
    val t = rememberWorkerStrings()
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
            Text(t("remove"))
        }
    }
}

@Composable
private fun StatusCard(status: MobileWorkerStatus, actions: @Composable () -> Unit) {
    val t = rememberWorkerStrings()
    val context = androidx.compose.ui.platform.LocalContext.current
    val delivery by AndroidWorkerEventDelivery.state.collectAsState()
    WorkerCard(t("connection")) {
        Text(t("registered"), color = workerColors.secondary, style = MaterialTheme.typography.bodySmall)
        Text(t("pending", "count" to status.pendingEventCount), color = if (status.pendingEventCount == 0) workerColors.onSurface else workerColors.tertiary)
        Text(t("lastDelivery", "time" to context.workerTime(status.lastSynchronizedAt)), style = MaterialTheme.typography.bodySmall)
        if (delivery == WorkerPhase.WAITING_DELIVERY) Text(t(delivery.key), style = MaterialTheme.typography.bodySmall)
        WorkerDetails(t("deviceDetails"), leadingAction = actions) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(status.workerId.orEmpty())
                Text(status.serverUrl.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
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
