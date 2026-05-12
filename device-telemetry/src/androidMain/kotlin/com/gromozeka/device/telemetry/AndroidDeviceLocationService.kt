package com.gromozeka.device.telemetry

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class AndroidDeviceLocationService(
    context: Context,
    private val permissionRequester: AndroidLocationPermissionRequester,
    private val timeout: Duration = 10.seconds,
) : DeviceLocationService {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override val isSupported: Boolean =
        locationManager != null && appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)

    override suspend fun getCurrentLocation(): DeviceLocationResult {
        val manager = locationManager
            ?: return DeviceLocationResult.Unavailable(DeviceLocationUnavailableReason.UNSUPPORTED)

        if (!hasForegroundLocationPermission() && !permissionRequester.requestForegroundLocationPermission()) {
            return DeviceLocationResult.Unavailable(DeviceLocationUnavailableReason.PERMISSION_DENIED)
        }

        val providers = enabledProviders(manager)
        if (providers.isEmpty()) {
            return DeviceLocationResult.Unavailable(DeviceLocationUnavailableReason.LOCATION_DISABLED)
        }

        return runCatching {
            val current = withTimeoutOrNull(timeout) {
                providers.firstNotNullOfOrNull { provider -> currentLocation(manager, provider) }
            }
            val location = current ?: providers.firstNotNullOfOrNull { provider -> lastKnownLocation(manager, provider) }
            location?.let { DeviceLocationResult.Available(it.toSnapshot()) }
                ?: DeviceLocationResult.Unavailable(DeviceLocationUnavailableReason.TIMEOUT)
        }.getOrElse { error ->
            DeviceLocationResult.Unavailable(DeviceLocationUnavailableReason.ERROR, error.message)
        }
    }

    private fun hasForegroundLocationPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasFineLocationPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun enabledProviders(manager: LocationManager): List<String> {
        val candidates = if (hasFineLocationPermission()) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }
        val enabled = manager.getProviders(true).toSet()
        return candidates.filter { it in enabled }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(manager: LocationManager, provider: String): Location? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            suspendCancellableCoroutine { continuation ->
                val cancellationSignal = CancellationSignal()
                continuation.invokeOnCancellation { cancellationSignal.cancel() }
                manager.getCurrentLocation(provider, cancellationSignal, appContext.mainExecutor) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            }
        } else {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(location)
                    }

                    override fun onProviderDisabled(provider: String) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(manager: LocationManager, provider: String): Location? =
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()

    private fun Location.toSnapshot(): DeviceLocationSnapshot =
        DeviceLocationSnapshot(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            altitudeMeters = if (hasAltitude()) altitude else null,
            bearingDegrees = if (hasBearing()) bearing else null,
            speedMetersPerSecond = if (hasSpeed()) speed else null,
            provider = provider,
            capturedAt = Instant.fromEpochMilliseconds(time),
        )
}

fun interface AndroidLocationPermissionRequester {
    suspend fun requestForegroundLocationPermission(): Boolean
}
