package com.gromozeka.mobile.worker

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import com.gromozeka.domain.model.AppActivityTransition
import com.gromozeka.domain.model.DeviceCollectionState
import com.gromozeka.domain.model.DeviceNetworkTransport
import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.domain.model.DeviceUsageTransition
import com.gromozeka.remote.protocol.WorkerEventInput
import java.security.MessageDigest
import java.util.TimeZone
import kotlin.time.Instant

internal class AndroidWorkerTelemetrySource(private val context: Context) : MobileWorkerTelemetrySource {
    override fun usageAccess(): DeviceCollectionState {
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return DeviceCollectionState.USER_LOCKED
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return DeviceCollectionState.ERROR
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return if (mode == AppOpsManager.MODE_ALLOWED) DeviceCollectionState.ACTIVE else DeviceCollectionState.PERMISSION_REQUIRED
    }

    override fun deviceState(): List<DeviceStateEvent> {
        val power = requireNotNull(context.getSystemService(PowerManager::class.java))
        val keyguard = requireNotNull(context.getSystemService(KeyguardManager::class.java))
        val network = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
        val active = network.activeNetwork
        val capabilities = active?.let(network::getNetworkCapabilities)
        val transports = buildSet {
            fun addTransport(id: Int, value: DeviceNetworkTransport) { if (capabilities?.hasTransport(id) == true) add(value) }
            addTransport(NetworkCapabilities.TRANSPORT_WIFI, DeviceNetworkTransport.WIFI)
            addTransport(NetworkCapabilities.TRANSPORT_CELLULAR, DeviceNetworkTransport.CELLULAR)
            addTransport(NetworkCapabilities.TRANSPORT_ETHERNET, DeviceNetworkTransport.ETHERNET)
            addTransport(NetworkCapabilities.TRANSPORT_VPN, DeviceNetworkTransport.VPN)
            addTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH, DeviceNetworkTransport.BLUETOOTH)
            if (active != null && isEmpty()) add(DeviceNetworkTransport.OTHER)
        }
        return buildList {
            add(DeviceStateEvent.ScreenState(power.isInteractive, keyguard.isKeyguardLocked, keyguard.isDeviceLocked))
            AndroidMobileWorkerSensors(context).battery()?.let {
                add(DeviceStateEvent.Battery(it.levelPercent, it.charging, power.isPowerSaveMode))
            }
            add(DeviceStateEvent.Environment(transports,
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                active?.let { network.isActiveNetworkMetered }, power.isPowerSaveMode, power.isDeviceIdleMode,
                TimeZone.getDefault().id,
                runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull(),
                SystemClock.elapsedRealtime()))
        }
    }

    @Suppress("DEPRECATION")
    override fun usageEvents(from: Instant, to: Instant, epoch: String): List<WorkerEventInput> {
        check(usageAccess() == DeviceCollectionState.ACTIVE) { "Usage access is unavailable" }
        val manager = requireNotNull(context.getSystemService(UsageStatsManager::class.java))
        val query = requireNotNull(manager.queryEvents(from.toEpochMilliseconds(), to.toEpochMilliseconds())) {
            "Android usage history is unavailable"
        }
        val event = UsageEvents.Event()
        val result = mutableListOf<WorkerEventInput>()
        val occurrence = mutableMapOf<String, Int>()
        var scanned = 0
        while (query.hasNextEvent()) {
            check(++scanned <= 100_000) { "Android usage query is too large" }
            query.getNextEvent(event)
            val observedAt = Instant.fromEpochMilliseconds(event.timeStamp)
            if (observedAt < from || observedAt >= to) continue
            val payload = payload(event) ?: continue
            val identity = "${event.timeStamp}\u0000${event.eventType}\u0000${event.packageName}\u0000${event.className}"
            val index = occurrence[identity] ?: 0
            occurrence[identity] = index + 1
            val digest = MessageDigest.getInstance("SHA-256").digest("$identity\u0000$index".toByteArray())
                .joinToString("") { "%02x".format(it) }
            result += WorkerEventInput("usage-$epoch-$digest", observedAt, payload)
            check(result.size <= MAX_USAGE_EVENTS_PER_QUERY) { "Android usage result is too large" }
        }
        check(usageAccess() == DeviceCollectionState.ACTIVE) { "Usage access changed during collection" }
        return result
    }

    @Suppress("DEPRECATION")
    private fun payload(event: UsageEvents.Event): DeviceStateEvent? {
        val activity = when (event.eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND -> AppActivityTransition.RESUMED
            UsageEvents.Event.MOVE_TO_BACKGROUND -> AppActivityTransition.PAUSED
            UsageEvents.Event.ACTIVITY_STOPPED -> AppActivityTransition.STOPPED
            else -> null
        }
        if (activity != null) {
            val packageName = event.packageName?.takeIf { it.isNotBlank() && it.length <= 255 } ?: return null
            return DeviceStateEvent.AppActivity(packageName, activity, event.className?.take(1_024))
        }
        val transition = when (event.eventType) {
            UsageEvents.Event.SCREEN_INTERACTIVE -> DeviceUsageTransition.SCREEN_INTERACTIVE
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> DeviceUsageTransition.SCREEN_NON_INTERACTIVE
            UsageEvents.Event.KEYGUARD_SHOWN -> DeviceUsageTransition.KEYGUARD_SHOWN
            UsageEvents.Event.KEYGUARD_HIDDEN -> DeviceUsageTransition.KEYGUARD_HIDDEN
            UsageEvents.Event.DEVICE_STARTUP -> DeviceUsageTransition.DEVICE_STARTUP
            UsageEvents.Event.DEVICE_SHUTDOWN -> DeviceUsageTransition.DEVICE_SHUTDOWN
            else -> return null
        }
        return DeviceStateEvent.UsageTransition(transition)
    }

    @Suppress("DEPRECATION")
    override fun applicationInfo(packageName: String): DeviceStateEvent.ApplicationInfo {
        val manager = context.packageManager
        val info = manager.getPackageInfo(packageName, 0)
        val app = requireNotNull(info.applicationInfo)
        return DeviceStateEvent.ApplicationInfo(packageName,
            manager.getApplicationLabel(app).toString().take(255), info.versionName?.take(255),
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            app.category.takeUnless { it == android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED }, true)
    }
}
