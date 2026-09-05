package com.gromozeka.mobile.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.domain.model.LocationCause
import com.gromozeka.worker.runtime.WorkerLocationConfiguration
import com.gromozeka.worker.runtime.WorkerLocationSample
import com.gromozeka.worker.runtime.isWorkerLocationFresh
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

internal class AndroidWorkerLocationSource(private val context: Context) {
    private val manager get() = requireNotNull(context.getSystemService(LocationManager::class.java)) { "Location sensor is unavailable" }

    fun hasPermission(): Boolean = permitted(Manifest.permission.ACCESS_COARSE_LOCATION) || permitted(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackgroundPermission(): Boolean = Build.VERSION.SDK_INT < 29 || permitted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun permitted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun provider(): String {
        require(hasPermission()) { "Location permission is not granted; open Android app permissions" }
        val providers = manager.getProviders(true)
        return listOfNotNull(
            LocationManager.GPS_PROVIDER.takeIf { permitted(Manifest.permission.ACCESS_FINE_LOCATION) },
            LocationManager.NETWORK_PROVIDER,
            if (Build.VERSION.SDK_INT >= 31) LocationManager.FUSED_PROVIDER else null,
        ).firstOrNull { it in providers } ?: error("No location provider is enabled; check Android location settings")
    }

    fun updates(configuration: WorkerLocationConfiguration) = callbackFlow {
        val provider = provider()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (trySend(location).isFailure) close(IllegalStateException("Location processing could not keep up with the sensor"))
            }
            override fun onProviderDisabled(name: String) { close(IllegalStateException("Location provider was disabled")) }
            override fun onProviderEnabled(name: String) = Unit
            @Deprecated("Required on Android 8")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            manager.requestLocationUpdates(provider, configuration.intervalSeconds * 1000L,
                configuration.minimumDistanceMeters.toFloat(), listener, Looper.getMainLooper())
            awaitClose { manager.removeUpdates(listener) }
        } finally {
            manager.removeUpdates(listener)
        }
    }

    suspend fun current(maximumAgeSeconds: Int): WorkerLocationSample {
        val started = SystemClock.elapsedRealtimeNanos()
        fun acceptable(location: Location): Boolean {
            val now = SystemClock.elapsedRealtimeNanos()
            return isWorkerLocationFresh(now - location.elapsedRealtimeNanos, now - started, maximumAgeSeconds)
        }
        if (maximumAgeSeconds > 0) {
            manager.getLastKnownLocation(provider())?.takeIf(::acceptable)?.let { return it.sample(LocationCause.CURRENT) }
        }
        return updates(WorkerLocationConfiguration(intervalSeconds = 1, minimumDistanceMeters = 0))
            .first(::acceptable).sample(LocationCause.CURRENT)
    }

    companion object {
        fun Location.sample(cause: LocationCause) = WorkerLocationSample(
            observedAt = Instant.fromEpochMilliseconds(time),
            location = DeviceStateEvent.Location(latitude, longitude,
                accuracy.takeIf { hasAccuracy() }?.toDouble(), altitude.takeIf { hasAltitude() },
                speed.takeIf { hasSpeed() }?.toDouble(), cause),
        )
    }
}
