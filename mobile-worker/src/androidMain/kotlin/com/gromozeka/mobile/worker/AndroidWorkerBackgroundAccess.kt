package com.gromozeka.mobile.worker

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

internal data class AndroidWorkerBackgroundAccess(
    val batteryOptimizationExempt: Boolean,
    val backgroundRestricted: Boolean,
) {
    fun settingsIntent(packageName: String): Intent = Intent(
        if (backgroundRestricted || batteryOptimizationExempt) Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        else Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.fromParts("package", packageName, null),
    )

    companion object {
        fun read(context: Context) = AndroidWorkerBackgroundAccess(
            batteryOptimizationExempt = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
            backgroundRestricted = Build.VERSION.SDK_INT >= 28 &&
                context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
        )
    }
}
