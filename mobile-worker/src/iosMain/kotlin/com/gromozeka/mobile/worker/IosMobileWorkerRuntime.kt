package com.gromozeka.mobile.worker

import com.gromozeka.domain.model.GeofenceTransition
import com.gromozeka.domain.model.LocationCause
import com.gromozeka.domain.model.MobileWorkerPlatform
import com.gromozeka.domain.model.SleepState
import kotlinx.datetime.Instant

class IosMobileWorkerRuntime(
    storage: MobileWorkerStorage,
    deviceName: String,
    operatingSystemVersion: String,
    appVersion: String,
) {
    private val runtime = MobileWorkerRuntime(
        storage = storage,
        platform = MobileWorkerPlatform.IOS,
        deviceName = deviceName,
        operatingSystemVersion = operatingSystemVersion,
        appVersion = appVersion,
    )

    @Throws(Exception::class)
    suspend fun enroll(
        serverUrl: String,
        enrollmentToken: String,
        workerId: String,
    ): IosMobileWorkerStatus = runtime.enroll(serverUrl, enrollmentToken, workerId).toIosStatus()

    @Throws(Exception::class)
    suspend fun status(): IosMobileWorkerStatus = runtime.status().toIosStatus()

    @Throws(Exception::class)
    suspend fun synchronize(): IosMobileWorkerStatus = runtime.synchronize().toIosStatus()

    @Throws(Exception::class)
    suspend fun reset() = runtime.reset()

    @Throws(Exception::class)
    suspend fun recordBattery(
        levelPercent: Int,
        charging: Boolean,
        lowPowerMode: Boolean,
        observedAtEpochMilliseconds: Long,
    ) = runtime.recordBattery(
        levelPercent,
        charging,
        lowPowerMode,
        observedAtEpochMilliseconds.toInstant(),
    )

    @Throws(Exception::class)
    suspend fun recordLocation(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double,
        altitudeMeters: Double,
        speedMetersPerSecond: Double,
        significantChange: Boolean,
        observedAtEpochMilliseconds: Long,
    ) = runtime.recordLocation(
        latitude,
        longitude,
        accuracyMeters.takeUnless(Double::isNaN),
        altitudeMeters.takeUnless(Double::isNaN),
        speedMetersPerSecond.takeUnless(Double::isNaN),
        if (significantChange) LocationCause.SIGNIFICANT_CHANGE else LocationCause.CURRENT,
        observedAtEpochMilliseconds.toInstant(),
    )

    @Throws(Exception::class)
    suspend fun recordGeofence(
        regionId: String,
        entered: Boolean,
        observedAtEpochMilliseconds: Long,
    ) = runtime.recordGeofence(
        regionId,
        if (entered) GeofenceTransition.ENTERED else GeofenceTransition.EXITED,
        observedAtEpochMilliseconds.toInstant(),
    )

    @Throws(Exception::class)
    suspend fun recordBlePresence(
        deviceId: String,
        displayName: String?,
        present: Boolean,
        observedAtEpochMilliseconds: Long,
    ) = runtime.recordBlePresence(
        deviceId,
        displayName,
        present,
        observedAtEpochMilliseconds.toInstant(),
    )

    @Throws(Exception::class)
    suspend fun recordVehicleAudioConnection(
        connected: Boolean,
        observedAtEpochMilliseconds: Long,
    ) = runtime.recordVehicleAudioConnection(
        connected,
        observedAtEpochMilliseconds.toInstant(),
    )

    @Throws(Exception::class)
    suspend fun recordSleep(
        asleep: Boolean,
        observedAtEpochMilliseconds: Long,
    ) = runtime.recordSleep(
        if (asleep) SleepState.ASLEEP else SleepState.AWAKE,
        observedAtEpochMilliseconds.toInstant(),
    )

    @Throws(Exception::class)
    suspend fun recordCompletedSleepSession(
        startedAtEpochMilliseconds: Long,
        endedAtEpochMilliseconds: Long,
    ) = runtime.recordCompletedSleepSession(
        startedAtEpochMilliseconds.toInstant(),
        endedAtEpochMilliseconds.toInstant(),
    )

    @Throws(Exception::class)
    suspend fun recordWifiConnection(
        networkId: String,
        connected: Boolean,
        observedAtEpochMilliseconds: Long,
    ) = runtime.recordWifiConnection(networkId, connected, observedAtEpochMilliseconds.toInstant())

    @Throws(Exception::class)
    suspend fun recordBluetoothPower(
        enabled: Boolean,
        observedAtEpochMilliseconds: Long,
    ) = runtime.recordBluetoothPower(enabled, observedAtEpochMilliseconds.toInstant())

    @Throws(Exception::class)
    suspend fun recordNfcTag(
        tagId: String,
        observedAtEpochMilliseconds: Long,
    ) = runtime.recordNfcTag(tagId, observedAtEpochMilliseconds.toInstant())

    @Throws(Exception::class)
    suspend fun recordAirplaneMode(
        enabled: Boolean,
        observedAtEpochMilliseconds: Long,
    ) = runtime.recordAirplaneMode(enabled, observedAtEpochMilliseconds.toInstant())

    @Throws(Exception::class)
    suspend fun recordCustomTrigger(
        name: String,
        attributes: Map<String, String>,
        observedAtEpochMilliseconds: Long,
    ) = runtime.recordCustomTrigger(name, attributes, observedAtEpochMilliseconds.toInstant())

    fun close() = runtime.close()
}

data class IosMobileWorkerStatus(
    val enrolled: Boolean,
    val serverUrl: String?,
    val workerId: String?,
    val pendingEventCount: Int,
    val lastSynchronizedAt: String?,
    val credentialAvailable: Boolean,
)

private fun MobileWorkerStatus.toIosStatus(): IosMobileWorkerStatus =
    IosMobileWorkerStatus(
        enrolled = enrolled,
        serverUrl = serverUrl,
        workerId = workerId,
        pendingEventCount = pendingEventCount,
        lastSynchronizedAt = lastSynchronizedAt?.toString(),
        credentialAvailable = credentialAvailable,
    )

private fun Long.toInstant(): Instant = Instant.fromEpochMilliseconds(this)
