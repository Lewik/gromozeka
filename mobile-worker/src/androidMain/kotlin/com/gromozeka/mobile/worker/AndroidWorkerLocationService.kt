package com.gromozeka.mobile.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.gromozeka.domain.model.LocationCause
import com.gromozeka.domain.model.WorkerAppState
import com.gromozeka.mobile.worker.AndroidWorkerLocationSource.Companion.sample
import com.gromozeka.worker.runtime.WorkerEventOutboxFullException
import com.gromozeka.worker.runtime.WorkerLocationSample
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidWorkerLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tracking: Job? = null
    private val syncSignal = Channel<Unit>(Channel.CONFLATED)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { syncSignal.trySend(Unit) }
    }
    private lateinit var runtime: MobileWorkerRuntime
    private lateinit var source: AndroidWorkerLocationSource
    private var collection: MobileWorkerLocationCollection? = null

    override fun onCreate() {
        super.onCreate()
        runtime = AndroidMobileWorkerRuntimeFactory.create(applicationContext)
        source = AndroidWorkerLocationSource(applicationContext)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                try {
                    runtime.configureLocation(runtime.status().locationConfiguration.copy(enabled = false))
                    stopSelf()
                } catch (error: Exception) {
                    updateState("Could not save disabled sharing: ${error::class.simpleName}")
                }
            }
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Worker location sharing", NotificationManager.IMPORTANCE_LOW))
            require(manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL_ID).importance != NotificationManager.IMPORTANCE_NONE) {
                "Allow location-sharing notifications before starting location sharing"
            }
            require(source.hasPermission()) { "Location permission is not granted" }
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION_ID, notification())
        } catch (error: Exception) {
            mutableState.value = "Location could not start: ${error.message ?: error::class.simpleName}"
            stopSelf()
            return START_NOT_STICKY
        }
        if (tracking?.isActive != true) {
            tracking = scope.launch {
                lifetime.withLock {
                    collection = runtime.locationCollection()
                    if (collection == null) { stopSelf(); return@withLock }
                    active = this@AndroidWorkerLocationService
                    val accessMonitor = launch {
                        while (isActive) {
                            delay(2_000)
                            try { requireLocationAccess() }
                            catch (error: Exception) {
                                updateState("Location paused: ${error.message}")
                                stopSelf()
                                return@launch
                            }
                        }
                    }
                    val delivery = launch(Dispatchers.IO) {
                        syncSignal.trySend(Unit)
                        while (isActive) {
                            withTimeoutOrNull(30_000) { syncSignal.receive() }
                            try {
                                do {
                                    val status = runtime.synchronize(WorkerAppState.BACKGROUND)
                                    mutableDelivery.value = "Last delivery: ${status.lastSynchronizedAt}"
                                } while (status.pendingEventCount > 0 && isActive)
                            } catch (error: CancellationException) { throw error }
                            catch (error: Exception) { mutableDelivery.value = "Waiting for connection; recorded points stay on this device" }
                        }
                    }
                    try {
                        var lastMeasurementNanos = SystemClock.elapsedRealtimeNanos()
                        while (isActive && runtime.locationCollection() == collection) {
                            try {
                                require(source.hasPermission()) { "Location permission was revoked" }
                                updateState("Waiting for a location fix")
                                source.updates(requireNotNull(collection).configuration).collect { location ->
                                    if (location.elapsedRealtimeNanos > lastMeasurementNanos) {
                                        requireLocationAccess()
                                        val sample = location.sample(LocationCause.LIVE_TRACKING)
                                        try {
                                            withContext(Dispatchers.IO) { runtime.recordSharedLocation(requireNotNull(collection), sample) }
                                            lastMeasurementNanos = location.elapsedRealtimeNanos
                                            updateState("Sharing · ${sample.observedAt} · accuracy ${sample.location.accuracyMeters ?: "unknown"} m")
                                        } catch (error: WorkerEventOutboxFullException) {
                                            updateState("Storage full: new points cannot be recorded until delivery resumes")
                                        }
                                        syncSignal.trySend(Unit)
                                    }
                                }
                            } catch (error: CancellationException) { throw error }
                            catch (error: Exception) {
                                updateState("Location paused: ${error.message ?: error::class.simpleName}")
                                delay(5_000)
                            }
                        }
                    } finally {
                        accessMonitor.cancelAndJoin()
                        delivery.cancelAndJoin()
                        if (active === this@AndroidWorkerLocationService) active = null
                    }
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private suspend fun locate(maximumAgeSeconds: Int, expectedStreamId: String): WorkerLocationSample {
        requireLocationAccess()
        val expected = requireNotNull(collection) { "Location sharing is not running" }
        require(expected.streamId == expectedStreamId) { "Worker enrollment changed" }
        require(runtime.locationCollection() == expected) { "Location sharing changed or was disabled" }
        val acquisition = scope.async { source.current(maximumAgeSeconds) }
        try {
            val sample = acquisition.await()
            requireLocationAccess()
            runtime.recordSharedLocation(expected, sample)
            syncSignal.trySend(Unit)
            return sample
        } finally { withContext(NonCancellable) { acquisition.cancelAndJoin() } }
    }

    private fun requireLocationAccess() {
        require(source.hasPermission()) { "Location permission was revoked" }
        val manager = getSystemService(NotificationManager::class.java)
        require(manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE) {
            "Location-sharing notifications were disabled"
        }
        require(Build.VERSION.SDK_INT < 29 || foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) {
            "Android stopped foreground location; allow background work and reopen the Worker"
        }
    }

    private fun updateState(value: String) {
        mutableState.value = value
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 0, Intent(this, AndroidWorkerLocationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Gromozeka · location sharing").setContentText(mutableState.value)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Disable location sharing", stop).build()).build()
    }

    override fun onDestroy() {
        if (active === this) active = null
        scope.cancel()
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        runtime.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (mutableState.value.startsWith("Sharing") || mutableState.value == "Waiting for a location fix") mutableState.value = "Location sharing stopped"
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "worker-location-sharing"
        private const val NOTIFICATION_ID = 27_046
        private const val ACTION_STOP = "com.gromozeka.mobile.worker.STOP_LOCATION"
        private val lifetime = Mutex()
        @Volatile private var active: AndroidWorkerLocationService? = null
        private val mutableState = MutableStateFlow("Location sharing is not running")
        val state = mutableState.asStateFlow()
        private val mutableDelivery = MutableStateFlow("")
        val delivery = mutableDelivery.asStateFlow()

        fun start(context: Context) { context.startForegroundService(Intent(context, AndroidWorkerLocationService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, AndroidWorkerLocationService::class.java)) }
        suspend fun currentLocation(maximumAgeSeconds: Int, expectedStreamId: String): WorkerLocationSample =
            requireNotNull(active) { "Location sharing is not running; enable it on this device" }.locate(maximumAgeSeconds, expectedStreamId)
    }
}
