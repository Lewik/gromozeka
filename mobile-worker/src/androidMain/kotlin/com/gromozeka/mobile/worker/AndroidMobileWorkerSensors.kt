package com.gromozeka.mobile.worker

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.ParcelUuid
import android.provider.Settings
import android.net.wifi.WifiManager
import android.util.Log
import com.gromozeka.domain.model.LocationCause

internal class AndroidMobileWorkerSensors(private val context: Context) {
    fun battery(): BatterySnapshot? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        return BatterySnapshot(
            levelPercent = ((level * 100.0) / scale).toInt().coerceIn(0, 100),
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            lowPowerMode = null,
        )
    }

    fun airplaneMode(): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

    fun bluetoothEnabled(): Boolean? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        return manager.adapter?.state == BluetoothAdapter.STATE_ON
    }

    fun wifiNetworkId(): String? {
        if (!canReadWifiState()) {
            return null
        }
        val manager = context.getSystemService(WifiManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        return manager.connectionInfo?.ssid
            ?.takeUnless { it == UNKNOWN_WIFI_SSID }
            ?.removeSurrounding("\"")
            ?.takeIf(String::isNotBlank)
    }

    fun lastKnownLocation(): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return manager.getProviders(true)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull(Location::getTime)
    }

    fun enableSignificantLocationUpdates(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        val preferredProviders = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        val provider = preferredProviders
            .firstOrNull { it in manager.getProviders(true) }
            ?: return false
        val intent = Intent(context, MobileWorkerLocationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            LOCATION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutablePendingIntentFlag(),
        )
        return runCatching {
            manager.requestLocationUpdates(
                provider,
                LOCATION_MIN_TIME_MILLIS,
                LOCATION_MIN_DISTANCE_METERS,
                pendingIntent,
            )
            synchronizeGeofences()
        }.onFailure { error ->
            Log.w(LOG_TAG, "Failed to enable background location signals", error)
        }.getOrDefault(false)
    }

    fun synchronizeGeofences(): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        val configured = AndroidMobileWorkerConfigurationStore(context).read().geofences
        val registeredIds = registeredGeofenceIds()
        (registeredIds + configured.map(ConfiguredGeofence::id)).forEach { id ->
            runCatching { manager.removeProximityAlert(geofencePendingIntent(id)) }
        }
        if (configured.isEmpty()) {
            writeRegisteredGeofenceIds(emptySet())
            return true
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            writeRegisteredGeofenceIds(emptySet())
            return false
        }
        val registered = configured.mapNotNullTo(linkedSetOf()) { geofence ->
            runCatching {
                manager.addProximityAlert(
                    geofence.latitude,
                    geofence.longitude,
                    geofence.radiusMeters.toFloat(),
                    NO_EXPIRATION,
                    geofencePendingIntent(geofence.id),
                )
                geofence.id
            }.onFailure { error ->
                Log.w(LOG_TAG, "Failed to register geofence ${geofence.id}", error)
            }.getOrNull()
        }
        writeRegisteredGeofenceIds(registered)
        return registered.size == configured.size
    }

    fun enableBlePresenceUpdates(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        val scanner = adapter.bluetoothLeScanner ?: return false
        val pendingIntent = blePendingIntent()
        scanner.stopScan(pendingIntent)
        val devices = AndroidMobileWorkerConfigurationStore(context).read().bleDevices
        if (devices.isEmpty()) return true
        val filters = devices.map { device ->
            ScanFilter.Builder().apply {
                device.address?.let(::setDeviceAddress)
                device.serviceUuid?.let { setServiceUuid(ParcelUuid.fromString(it)) }
            }.build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST)
            .build()
        return scanner.startScan(filters, settings, pendingIntent) == 0
    }

    fun disableBackgroundSignals() {
        val locationManager = context.getSystemService(LocationManager::class.java)
        runCatching { locationManager?.removeUpdates(locationPendingIntent()) }
        registeredGeofenceIds().forEach { id ->
            runCatching { locationManager?.removeProximityAlert(geofencePendingIntent(id)) }
        }
        writeRegisteredGeofenceIds(emptySet())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val scanner = context.getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?.bluetoothLeScanner
        runCatching { scanner?.stopScan(blePendingIntent()) }
    }

    suspend fun captureConfiguredState(runtime: MobileWorkerRuntime) {
        val configuration = AndroidMobileWorkerConfigurationStore(context).read()
        if (!canReadWifiState()) return
        configuration.wifiNetworkId?.let { selectedNetwork ->
            runtime.recordWifiConnection(selectedNetwork, wifiNetworkId() == selectedNetwork)
        }
    }

    private fun canReadWifiState(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun locationPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        LOCATION_REQUEST_CODE,
        Intent(context, MobileWorkerLocationReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or mutablePendingIntentFlag(),
    )

    private fun geofencePendingIntent(regionId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        GEOFENCE_REQUEST_CODE,
        Intent(context, MobileWorkerGeofenceReceiver::class.java).apply {
            action = GEOFENCE_ACTION
            data = Uri.Builder()
                .scheme(GEOFENCE_URI_SCHEME)
                .authority(GEOFENCE_URI_AUTHORITY)
                .appendPath(regionId)
                .build()
        },
        PendingIntent.FLAG_UPDATE_CURRENT or mutablePendingIntentFlag(),
    )

    private fun registeredGeofenceIds(): Set<String> =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getStringSet(REGISTERED_GEOFENCE_IDS_KEY, emptySet())
            ?.toSet()
            .orEmpty()

    private fun writeRegisteredGeofenceIds(ids: Set<String>) {
        check(
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(REGISTERED_GEOFENCE_IDS_KEY, ids)
                .commit()
        ) {
            "Registered geofence IDs could not be persisted"
        }
    }

    private fun blePendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        BLE_REQUEST_CODE,
        Intent(context, MobileWorkerBleReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    data class BatterySnapshot(
        val levelPercent: Int,
        val charging: Boolean,
        val lowPowerMode: Boolean?,
    )

    companion object {
        fun geofenceRegionId(intent: Intent): String? =
            intent.data
                ?.takeIf { it.scheme == GEOFENCE_URI_SCHEME && it.authority == GEOFENCE_URI_AUTHORITY }
                ?.lastPathSegment
                ?.takeIf(String::isNotBlank)

        fun locationFrom(intent: Intent): Location? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED, Location::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED)
            }

        suspend fun Location.record(runtime: MobileWorkerRuntime) =
            runtime.recordLocation(
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = accuracy.takeIf { hasAccuracy() }?.toDouble(),
                altitudeMeters = altitude.takeIf { hasAltitude() },
                speedMetersPerSecond = speed.takeIf { hasSpeed() }?.toDouble(),
                cause = LocationCause.SIGNIFICANT_CHANGE,
                observedAt = kotlin.time.Instant.fromEpochMilliseconds(time),
            )

        private const val LOCATION_REQUEST_CODE = 27_041
        private const val GEOFENCE_REQUEST_CODE = 27_044
        private const val BLE_REQUEST_CODE = 27_043
        private const val LOCATION_MIN_TIME_MILLIS = 15 * 60 * 1_000L
        private const val LOCATION_MIN_DISTANCE_METERS = 500f
        private const val UNKNOWN_WIFI_SSID = "<unknown ssid>"
        private const val NO_EXPIRATION = -1L
        private const val PREFERENCES_NAME = "gromozeka-mobile-worker-signals"
        private const val REGISTERED_GEOFENCE_IDS_KEY = "registered-geofence-ids"
        private const val GEOFENCE_ACTION = "com.gromozeka.mobile.worker.GEOFENCE_TRANSITION"
        private const val GEOFENCE_URI_SCHEME = "gromozeka-worker"
        private const val GEOFENCE_URI_AUTHORITY = "geofence"
        private const val LOG_TAG = "GromozekaMobileWorker"
    }
}

private fun mutablePendingIntentFlag(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
