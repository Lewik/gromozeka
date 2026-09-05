package com.gromozeka.mobile.worker

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal object AndroidMobileWorkerRuntimeFactory {
    fun create(context: Context): MobileWorkerRuntime =
        MobileWorkerRuntime(
            storage = AndroidMobileWorkerStorage(context),
            platform = com.gromozeka.domain.model.WorkerPlatform.ANDROID,
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            operatingSystemVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            appVersion = context.applicationVersion(),
            onEventsQueued = { MobileWorkerSyncJobService.requestSynchronization(context.applicationContext) },
        )
}

internal fun Context.applicationVersion(): String {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }
    return packageInfo.versionName ?: "unknown"
}
