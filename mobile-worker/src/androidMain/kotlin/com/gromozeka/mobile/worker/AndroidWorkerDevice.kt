package com.gromozeka.mobile.worker

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.domain.model.WorkerPlatform
import com.gromozeka.domain.service.WorkerEnvironmentProfile
import com.gromozeka.domain.service.WorkerNativeShell
import com.gromozeka.domain.service.WorkerOperatingSystem
import com.gromozeka.worker.runtime.WorkerDeviceStatus
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Clock

internal class AndroidWorkerDevice(private val context: Context, private val runtime: MobileWorkerRuntime) {
    fun profile(): WorkerEnvironmentProfile {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        return WorkerEnvironmentProfile(
            observedAt = Clock.System.now(),
            operatingSystem = WorkerOperatingSystem(WorkerOperatingSystem.Family.LINUX, "Android", Build.VERSION.RELEASE),
            architecture = Build.SUPPORTED_ABIS.first(),
            nativeShell = WorkerNativeShell(WorkerNativeShell.Kind.POSIX_SH, "/system/bin/sh"),
            timezoneId = TimeZone.getDefault().id,
            localeTag = Locale.getDefault().toLanguageTag(),
            logicalProcessorCount = Runtime.getRuntime().availableProcessors(),
            totalMemoryBytes = memory.totalMem,
            availableExecutables = emptyList(),
        )
    }

    suspend fun status(): WorkerDeviceStatus {
        val sensors = AndroidMobileWorkerSensors(context)
        val battery = sensors.battery()
        return WorkerDeviceStatus(
            observedAt = Clock.System.now(),
            device = DeviceStateEvent.DeviceInfo(WorkerPlatform.ANDROID,
                "${Build.MANUFACTURER} ${Build.MODEL}".trim(), "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", context.applicationVersion()),
            battery = battery?.let { DeviceStateEvent.Battery(it.levelPercent, it.charging, context.getSystemService(PowerManager::class.java).isPowerSaveMode) },
            airplaneMode = sensors.airplaneMode(),
            bluetoothEnabled = sensors.bluetoothEnabled(),
            availableStorageBytes = context.noBackupFilesDir.usableSpace,
            pendingEventCount = runtime.status().pendingEventCount,
        )
    }
}
