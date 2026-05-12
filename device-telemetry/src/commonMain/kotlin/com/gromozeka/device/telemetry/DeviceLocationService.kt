package com.gromozeka.device.telemetry

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

interface DeviceLocationService {
    val isSupported: Boolean

    suspend fun getCurrentLocation(): DeviceLocationResult
}

@Serializable
data class DeviceLocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val altitudeMeters: Double?,
    val bearingDegrees: Float?,
    val speedMetersPerSecond: Float?,
    val provider: String?,
    val capturedAt: Instant,
)

@Serializable
sealed interface DeviceLocationResult {
    @Serializable
    data class Available(val snapshot: DeviceLocationSnapshot) : DeviceLocationResult

    @Serializable
    data class Unavailable(
        val reason: DeviceLocationUnavailableReason,
        val message: String? = null,
    ) : DeviceLocationResult
}

@Serializable
enum class DeviceLocationUnavailableReason {
    UNSUPPORTED,
    PERMISSION_DENIED,
    LOCATION_DISABLED,
    TIMEOUT,
    ERROR,
}

object NoOpDeviceLocationService : DeviceLocationService {
    override val isSupported: Boolean = false

    override suspend fun getCurrentLocation(): DeviceLocationResult =
        DeviceLocationResult.Unavailable(DeviceLocationUnavailableReason.UNSUPPORTED)
}
