package com.gromozeka.mobile.worker

import android.content.Context
import android.os.Build

internal object AndroidMobileWorkerRuntimeFactory {
    fun create(context: Context): MobileWorkerRuntime =
        MobileWorkerRuntime(
            storage = AndroidMobileWorkerStorage(context),
            platform = com.gromozeka.domain.model.MobileWorkerPlatform.ANDROID,
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            operatingSystemVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            appVersion = BuildConfig.VERSION_NAME,
        )
}
