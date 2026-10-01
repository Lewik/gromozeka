package com.gromozeka.mobile.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.gromozeka.worker.runtime.WorkerEventOutboxFullException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Visible, opt-in collection. No network constraint and no remote-command dependency. */
class AndroidWorkerTelemetryService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sampleSignal = Channel<Unit>(Channel.CONFLATED)
    private var collectionJob: Job? = null
    private lateinit var runtime: MobileWorkerRuntime
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { sampleSignal.trySend(Unit) }
    }

    override fun onCreate() {
        super.onCreate()
        runtime = AndroidMobileWorkerRuntimeFactory.create(applicationContext)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        runtime.configureTelemetry(runtime.status().telemetryConfiguration.copy(enabled = false))
                    }
                    stopSelf()
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { mutableState.value = getString(R.string.telemetry_save_failed) }
            }
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
                getString(R.string.telemetry_title), NotificationManager.IMPORTANCE_LOW))
            check(notificationsAllowed(this)) { "Telemetry notifications are disabled" }
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification())
        } catch (_: Exception) {
            mutableState.value = getString(R.string.telemetry_notifications_required)
            stopSelf()
            return START_NOT_STICKY
        }
        if (collectionJob?.isActive != true) {
            collectionJob = scope.launch {
                lifetime.withLock {
                    AndroidWorkerEventDelivery.acquire(applicationContext, this@AndroidWorkerTelemetryService)
                    try {
                        val collector = MobileWorkerTelemetryCollector(runtime, AndroidWorkerTelemetrySource(applicationContext))
                        while (isActive) {
                            val configuration = withContext(Dispatchers.IO) { runtime.telemetryCollection()?.configuration } ?: break
                            if (!notificationsAllowed(this@AndroidWorkerTelemetryService)) {
                                mutableState.value = getString(R.string.telemetry_notifications_required)
                                break
                            }
                            var catchUp = false
                            try {
                                catchUp = withContext(Dispatchers.IO) { collector.collect() }
                                mutableState.value = getString(R.string.telemetry_collecting)
                            } catch (error: CancellationException) { throw error }
                            catch (_: WorkerEventOutboxFullException) {
                                mutableState.value = getString(R.string.telemetry_storage_full)
                            } catch (_: Exception) {
                                mutableState.value = getString(R.string.telemetry_collection_failed)
                            }
                            AndroidWorkerEventDelivery.wake()
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                            if (catchUp) delay(250)
                            else withTimeoutOrNull(configuration.intervalSeconds * 1_000L) { sampleSignal.receive() }
                        }
                    } finally {
                        AndroidWorkerEventDelivery.release(this@AndroidWorkerTelemetryService)
                    }
                    stopSelf()
                }
            }
        } else sampleSignal.trySend(Unit)
        return START_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 0, Intent(this, AndroidWorkerTelemetryService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.telemetry_title)).setContentText(getString(R.string.telemetry_notification))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, getString(R.string.telemetry_disable), stop).build()).build()
    }

    override fun onDestroy() {
        scope.cancel()
        AndroidWorkerEventDelivery.release(this)
        runCatching { unregisterReceiver(screenReceiver) }
        runtime.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "worker-telemetry"
        private const val NOTIFICATION_ID = 27_047
        private const val ACTION_STOP = "com.gromozeka.mobile.worker.STOP_TELEMETRY"
        private val lifetime = Mutex()
        private val mutableState = MutableStateFlow("")
        val state = mutableState.asStateFlow()

        fun start(context: Context) { context.startForegroundService(Intent(context, AndroidWorkerTelemetryService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, AndroidWorkerTelemetryService::class.java)) }
        fun notificationsAllowed(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}
