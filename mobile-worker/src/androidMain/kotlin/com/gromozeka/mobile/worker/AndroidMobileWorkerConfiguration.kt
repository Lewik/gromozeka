package com.gromozeka.mobile.worker

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
internal data class AndroidMobileWorkerConfiguration(
    val geofences: List<ConfiguredGeofence> = emptyList(),
    val bleDevices: List<ConfiguredBleDevice> = emptyList(),
    val wifiNetworkId: String? = null,
)

@Serializable
internal data class ConfiguredGeofence(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
) {
    init {
        require(id.isNotBlank()) { "Geofence ID must not be blank" }
        require(id.length <= MAX_SIGNAL_IDENTIFIER_LENGTH) { "Geofence ID is too long" }
        require(latitude in -90.0..90.0) { "Geofence latitude is outside its valid range" }
        require(longitude in -180.0..180.0) { "Geofence longitude is outside its valid range" }
        require(radiusMeters in 50.0..100_000.0) { "Geofence radius must be between 50 and 100000 meters" }
    }
}

@Serializable
internal data class ConfiguredBleDevice(
    val id: String,
    val displayName: String? = null,
    val address: String? = null,
    val serviceUuid: String? = null,
) {
    init {
        require(id.isNotBlank()) { "BLE device ID must not be blank" }
        require(id.length <= MAX_SIGNAL_IDENTIFIER_LENGTH) { "BLE device ID is too long" }
        require(displayName == null || displayName.length <= MAX_SIGNAL_LABEL_LENGTH) {
            "BLE device display name is too long"
        }
        require((address == null) xor (serviceUuid == null)) {
            "A BLE device must have exactly one address or service UUID selector"
        }
    }
}

internal class AndroidMobileWorkerConfigurationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): AndroidMobileWorkerConfiguration = synchronized(configurationLock) {
        preferences.getString(CONFIGURATION_KEY, null)
            ?.let(json::decodeFromString)
            ?: AndroidMobileWorkerConfiguration()
    }

    fun addGeofence(
        id: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
    ): AndroidMobileWorkerConfiguration = update { current ->
        val geofence = ConfiguredGeofence(id.trim(), latitude, longitude, radiusMeters)
        require(geofence.id in current.geofences.map(ConfiguredGeofence::id) || current.geofences.size < MAX_GEOFENCES) {
            "At most $MAX_GEOFENCES geofences can be configured"
        }
        current.copy(
            geofences = current.geofences.filterNot { it.id == geofence.id } + geofence,
        )
    }

    fun removeGeofence(id: String): AndroidMobileWorkerConfiguration = update { current ->
        current.copy(geofences = current.geofences.filterNot { it.id == id })
    }

    fun addBleDevice(
        displayName: String?,
        selector: String,
    ): AndroidMobileWorkerConfiguration = update { current ->
        val normalizedSelector = selector.trim()
        val address = normalizedSelector.takeIf { it.matches(bleAddressPattern) }?.uppercase()
        val serviceUuid = if (address == null) UUID.fromString(normalizedSelector).toString() else null
        val id = address ?: serviceUuid.orEmpty()
        val device = ConfiguredBleDevice(id, displayName?.trim()?.takeIf(String::isNotBlank), address, serviceUuid)
        require(id in current.bleDevices.map(ConfiguredBleDevice::id) || current.bleDevices.size < MAX_BLE_DEVICES) {
            "At most $MAX_BLE_DEVICES BLE devices can be configured"
        }
        current.copy(bleDevices = current.bleDevices.filterNot { it.id == id } + device)
    }

    fun removeBleDevice(id: String): AndroidMobileWorkerConfiguration = update { current ->
        current.copy(bleDevices = current.bleDevices.filterNot { it.id == id })
    }

    fun setWifiNetworkId(value: String?): AndroidMobileWorkerConfiguration = update { current ->
        val networkId = value?.trim()?.takeIf(String::isNotBlank)
        require(networkId == null || networkId.length <= MAX_SIGNAL_IDENTIFIER_LENGTH) {
            "Wi-Fi network ID is too long"
        }
        current.copy(wifiNetworkId = networkId)
    }

    fun clear(): AndroidMobileWorkerConfiguration = synchronized(configurationLock) {
        check(preferences.edit().clear().commit()) {
            "Mobile Worker signal configuration could not be removed"
        }
        AndroidMobileWorkerConfiguration()
    }

    private fun update(
        transform: (AndroidMobileWorkerConfiguration) -> AndroidMobileWorkerConfiguration,
    ): AndroidMobileWorkerConfiguration = synchronized(configurationLock) {
        transform(read()).also(::write)
    }

    private fun write(configuration: AndroidMobileWorkerConfiguration) {
        check(preferences.edit().putString(CONFIGURATION_KEY, json.encodeToString(configuration)).commit()) {
            "Mobile Worker signal configuration could not be persisted"
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "gromozeka-mobile-worker-signals"
        private const val CONFIGURATION_KEY = "configuration"
        private val bleAddressPattern = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")
        private val configurationLock = Any()
        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        }
    }
}

private const val MAX_SIGNAL_IDENTIFIER_LENGTH = 128
private const val MAX_SIGNAL_LABEL_LENGTH = 255
private const val MAX_GEOFENCES = 100
private const val MAX_BLE_DEVICES = 100
