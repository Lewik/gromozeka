package com.gromozeka.mobile.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.gromozeka.worker.runtime.KtorWorkerGatewayTransport
import com.gromozeka.worker.runtime.WorkerDeviceStatusTool
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class AndroidWorkerGatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connection: Job? = null
    @Volatile private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showForegroundNotification()
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                val runtime = AndroidMobileWorkerRuntimeFactory.create(applicationContext)
                try {
                    runtime.setGatewayEnabled(false)
                } catch (error: Exception) {
                    androidMobileWorkerLog.error { "Could not persist disabled remote commands: ${error::class.simpleName}" }
                } finally {
                    runtime.close()
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }
        if (connection?.isActive != true) {
            connection = scope.launch {
                gatewayLifetime.withLock {
                    val runtime = AndroidMobileWorkerRuntimeFactory.create(applicationContext)
                    try {
                        val enrollment = runtime.gatewayEnrollment()
                        if (enrollment == null) {
                            stopSelf()
                            return@withLock
                        }
                        updateState(MobileWorkerGatewayState.CONNECTING)
                        AndroidWorkerRequestJournal(applicationContext, enrollment.streamId).use { persistence ->
                            val client = HttpClient(OkHttp) {
                                followRedirects = false
                                engine {
                                    config {
                                        followRedirects(false)
                                        followSslRedirects(false)
                                        connectTimeout(15, TimeUnit.SECONDS)
                                        pingInterval(20, TimeUnit.SECONDS)
                                    }
                                }
                                install(WebSockets) { maxFrameSize = 1024 * 1024 }
                            }
                            try {
                                val device = AndroidWorkerDevice(applicationContext, runtime)
                                MobileWorkerGateway(
                                    enrollment = enrollment,
                                    transport = KtorWorkerGatewayTransport(client, enrollment.gatewayUrl, enrollment.credential),
                                    journal = persistence.journal,
                                    profile = device.profile(),
                                    version = applicationContext.applicationVersion(),
                                    tools = listOf(WorkerDeviceStatusTool(device::status)),
                                    beforeExecution = {
                                        check(runtime.gatewayEnrollment()?.streamId == enrollment.streamId) {
                                            "Remote commands are disabled or Worker enrollment changed"
                                        }
                                    },
                                    onState = ::updateState,
                                    onFailure = { error, attempts ->
                                        androidMobileWorkerLog.warn { "Worker Gateway connection failed: attempts=$attempts error=${error::class.simpleName}" }
                                    },
                                ).run()
                            } finally { client.close() }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        updateState(MobileWorkerGatewayState.FAILED)
                        androidMobileWorkerLog.error { "Worker Gateway stopped: ${error::class.simpleName}" }
                        stopSelf()
                    } finally {
                        runtime.close()
                        if (mutableState.value != MobileWorkerGatewayState.FAILED) mutableState.value = MobileWorkerGatewayState.STOPPED
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun updateState(state: MobileWorkerGatewayState) {
        if (destroyed) return
        mutableState.value = state
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun showForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Worker remote commands", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, AndroidWorkerGatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = when (mutableState.value) {
            MobileWorkerGatewayState.CONNECTED -> "Connected. Remote device commands are enabled."
            MobileWorkerGatewayState.FAILED -> "Connection stopped. Open the Worker to retry."
            else -> "Remote commands enabled. Waiting for the server."
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Gromozeka Worker")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Disable commands", stop).build())
            .build()
    }

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "worker-remote-commands"
        private const val NOTIFICATION_ID = 27_045
        private const val ACTION_STOP = "com.gromozeka.mobile.worker.DISABLE_COMMANDS"
        private val gatewayLifetime = Mutex()
        private val mutableState = MutableStateFlow(MobileWorkerGatewayState.STOPPED)
        internal val state = mutableState.asStateFlow()

        internal fun start(context: Context) {
            context.startForegroundService(Intent(context, AndroidWorkerGatewayService::class.java))
        }

        internal fun stop(context: Context) {
            context.stopService(Intent(context, AndroidWorkerGatewayService::class.java))
        }
    }
}
