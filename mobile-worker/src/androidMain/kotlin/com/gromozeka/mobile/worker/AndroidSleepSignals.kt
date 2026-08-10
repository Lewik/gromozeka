package com.gromozeka.mobile.worker

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit

internal class AndroidSleepSignals(private val context: Context) {
    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    fun requestedPermissions(): Set<String> {
        if (!isAvailable()) return emptySet()
        val client = HealthConnectClient.getOrCreate(context)
        return buildSet {
            add(sleepReadPermission)
            if (client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            ) {
                add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
            }
        }
    }

    fun hasSleepReadPermission(grantedPermissions: Set<String>): Boolean =
        sleepReadPermission in grantedPermissions

    suspend fun captureLatestSession(runtime: MobileWorkerRuntime): Boolean {
        if (!isAvailable()) return false
        val client = HealthConnectClient.getOrCreate(context)
        if (sleepReadPermission !in client.permissionController.getGrantedPermissions()) return false
        val sessions = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.after(Instant.now().minus(36, ChronoUnit.HOURS)),
                ascendingOrder = false,
            )
        ).records
        val latest = sessions.maxByOrNull(SleepSessionRecord::endTime) ?: return false
        val signature = "${latest.startTime.toEpochMilli()}:${latest.endTime.toEpochMilli()}"
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (preferences.getString(LAST_SESSION_KEY, null) == signature) return false
        runtime.recordCompletedSleepSession(
            startedAt = kotlin.time.Instant.fromEpochMilliseconds(latest.startTime.toEpochMilli()),
            endedAt = kotlin.time.Instant.fromEpochMilliseconds(latest.endTime.toEpochMilli()),
        )
        check(preferences.edit().putString(LAST_SESSION_KEY, signature).commit()) {
            "Latest sleep session marker could not be persisted"
        }
        return true
    }

    companion object {
        val permissionContract: ActivityResultContract<Set<String>, Set<String>>
            get() = PermissionController.createRequestPermissionResultContract()

        private val sleepReadPermission = HealthPermission.getReadPermission(SleepSessionRecord::class)
        private const val PREFERENCES_NAME = "gromozeka-mobile-worker-signals"
        private const val LAST_SESSION_KEY = "last-sleep-session"
    }
}
