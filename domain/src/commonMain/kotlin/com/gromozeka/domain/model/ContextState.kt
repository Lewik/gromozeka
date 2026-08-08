package com.gromozeka.domain.model

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class ContextEventId(val value: String) {
    init {
        require(value.isNotBlank()) { "Context event ID must not be blank" }
        require(value.length <= 255) { "Context event ID must not exceed 255 characters" }
    }
}

@Serializable
data class ContextEvent(
    val id: ContextEventId,
    val userId: User.Id,
    val source: Source,
    val subject: Subject,
    val payload: Payload,
    val observedAt: Instant,
    val receivedAt: Instant,
) {
    init {
        require(receivedAt >= observedAt || source is Source.MobileWorker) {
            "Server-originated context events cannot be received before they are observed"
        }
        require(subject !is Subject.UserState || subject.userId == userId) {
            "User context event subject must match its owner"
        }
        require(source !is Source.MobileWorker || subject == Subject.Device(source.workerId)) {
            "Mobile Worker context events must describe their own device"
        }
        require(source !is Source.UserDeclaration || source.userId == userId) {
            "User declaration source must match its owner"
        }
        require(payload !is Payload.Device || subject is Subject.Device) {
            "Device state payload requires a device subject"
        }
        require(payload !is Payload.ActiveClient || subject is Subject.UserState) {
            "Active client payload requires a user subject"
        }
        require(payload !is Payload.UserDeclaration || source is Source.UserDeclaration) {
            "User declaration payload requires a user declaration source"
        }
        if (source is Source.Client && payload is Payload.ActiveClient) {
            require(source.instanceId == payload.instanceId && source.sessionId == payload.sessionId) {
                "Active client payload must match its client source"
            }
        }
    }

    @Serializable
    sealed interface Source {
        @Serializable
        @SerialName("mobile_worker")
        data class MobileWorker(val workerId: ConversationRuntimeWorkerId) : Source

        @Serializable
        @SerialName("client")
        data class Client(
            val instanceId: String,
            val sessionId: String,
            val platform: ClientPlatform,
        ) : Source {
            init {
                require(instanceId.isNotBlank()) { "Client instance ID must not be blank" }
                require(sessionId.isNotBlank()) { "Client session ID must not be blank" }
                require(instanceId.length <= 255) { "Client instance ID must not exceed 255 characters" }
                require(sessionId.length <= 255) { "Client session ID must not exceed 255 characters" }
            }
        }

        @Serializable
        @SerialName("user")
        data class UserDeclaration(val userId: User.Id) : Source

        @Serializable
        @SerialName("server")
        data object Server : Source
    }

    @Serializable
    sealed interface Subject {
        @Serializable
        @SerialName("user")
        data class UserState(val userId: User.Id) : Subject

        @Serializable
        @SerialName("device")
        data class Device(val workerId: ConversationRuntimeWorkerId) : Subject
    }

    @Serializable
    sealed interface Payload {
        @Serializable
        @SerialName("device")
        data class Device(val event: DeviceStateEvent) : Payload

        @Serializable
        @SerialName("active_client")
        data class ActiveClient(
            val instanceId: String,
            val sessionId: String,
            val platform: ClientPlatform,
            val activity: ClientActivity,
            val active: Boolean,
        ) : Payload

        @Serializable
        @SerialName("user_declaration")
        data class UserDeclaration(
            val stateKey: String,
            val value: JsonElement,
        ) : Payload {
            init {
                require(stateKey.matches(stateKeyPattern)) {
                    "Declared state key must contain only letters, digits, dots, dashes, underscores, or colons"
                }
            }
        }
    }
}

@Serializable
enum class ClientPlatform {
    DESKTOP,
    ANDROID,
    IOS,
    WEB_DESKTOP,
    WEB_TOUCH,
}

@Serializable
enum class ClientActivity {
    WINDOW_FOCUSED,
    USER_INTERACTION,
    RECONNECTED,
    DISCONNECTED,
}

@Serializable
sealed interface DeviceStateEvent {
    @Serializable
    @SerialName("device_info")
    data class DeviceInfo(
        val platform: MobileWorkerPlatform,
        val deviceName: String,
        val operatingSystemVersion: String,
        val appVersion: String,
    ) : DeviceStateEvent {
        init {
            require(deviceName.isNotBlank()) { "Device name must not be blank" }
            require(operatingSystemVersion.isNotBlank()) { "Operating system version must not be blank" }
            require(appVersion.isNotBlank()) { "Mobile Worker version must not be blank" }
            require(deviceName.length <= MAX_CONTEXT_LABEL_LENGTH) { "Device name is too long" }
            require(operatingSystemVersion.length <= MAX_CONTEXT_LABEL_LENGTH) {
                "Operating system version is too long"
            }
            require(appVersion.length <= MAX_CONTEXT_LABEL_LENGTH) { "Mobile Worker version is too long" }
        }
    }

    @Serializable
    @SerialName("battery")
    data class Battery(
        val levelPercent: Int,
        val charging: Boolean,
        val lowPowerMode: Boolean? = null,
    ) : DeviceStateEvent {
        init {
            require(levelPercent in 0..100) { "Battery level must be between 0 and 100" }
        }
    }

    @Serializable
    @SerialName("location")
    data class Location(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Double?,
        val altitudeMeters: Double? = null,
        val speedMetersPerSecond: Double? = null,
        val cause: LocationCause,
    ) : DeviceStateEvent {
        init {
            require(latitude.isFinite() && latitude in -90.0..90.0) { "Latitude is outside its valid range" }
            require(longitude.isFinite() && longitude in -180.0..180.0) {
                "Longitude is outside its valid range"
            }
            require(accuracyMeters == null || accuracyMeters.isFinite() && accuracyMeters >= 0) {
                "Location accuracy must be finite and non-negative"
            }
            require(altitudeMeters == null || altitudeMeters.isFinite()) { "Location altitude must be finite" }
            require(speedMetersPerSecond == null || speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0) {
                "Location speed must be finite and non-negative"
            }
        }
    }

    @Serializable
    @SerialName("geofence")
    data class Geofence(
        val regionId: String,
        val transition: GeofenceTransition,
    ) : DeviceStateEvent {
        init {
            require(regionId.isNotBlank()) { "Geofence region ID must not be blank" }
            require(regionId.length <= MAX_CONTEXT_IDENTIFIER_LENGTH) { "Geofence region ID is too long" }
        }
    }

    @Serializable
    @SerialName("ble_presence")
    data class BlePresence(
        val deviceId: String,
        val displayName: String?,
        val present: Boolean,
    ) : DeviceStateEvent {
        init {
            require(deviceId.isNotBlank()) { "BLE device ID must not be blank" }
            require(deviceId.length <= MAX_CONTEXT_IDENTIFIER_LENGTH) { "BLE device ID is too long" }
            require(displayName == null || displayName.length <= MAX_CONTEXT_LABEL_LENGTH) {
                "BLE device display name is too long"
            }
        }
    }

    @Serializable
    @SerialName("vehicle_connection")
    data class VehicleConnection(
        val system: VehicleSystem,
        val connected: Boolean,
    ) : DeviceStateEvent

    @Serializable
    @SerialName("vehicle_audio_connected")
    data class VehicleAudioConnection(val connected: Boolean) : DeviceStateEvent

    @Serializable
    @SerialName("sleep")
    data class Sleep(val state: SleepState) : DeviceStateEvent

    @Serializable
    @SerialName("wifi_connection")
    data class WifiConnection(
        val networkId: String,
        val connected: Boolean,
    ) : DeviceStateEvent {
        init {
            require(networkId.isNotBlank()) { "Wi-Fi network ID must not be blank" }
            require(networkId.length <= MAX_CONTEXT_IDENTIFIER_LENGTH) { "Wi-Fi network ID is too long" }
        }
    }

    @Serializable
    @SerialName("bluetooth_power")
    data class BluetoothPower(val enabled: Boolean) : DeviceStateEvent

    @Serializable
    @SerialName("nfc_tag")
    data class NfcTag(val tagId: String) : DeviceStateEvent {
        init {
            require(tagId.isNotBlank()) { "NFC tag ID must not be blank" }
            require(tagId.length <= MAX_CONTEXT_LABEL_LENGTH) { "NFC tag ID is too long" }
        }
    }

    @Serializable
    @SerialName("airplane_mode")
    data class AirplaneMode(val enabled: Boolean) : DeviceStateEvent

    @Serializable
    @SerialName("custom_trigger")
    data class CustomTrigger(
        val name: String,
        val attributes: Map<String, String> = emptyMap(),
    ) : DeviceStateEvent {
        init {
            require(name.isNotBlank()) { "Custom trigger name must not be blank" }
            require(name.length <= MAX_CONTEXT_IDENTIFIER_LENGTH) { "Custom trigger name is too long" }
            require(attributes.size <= MAX_CUSTOM_TRIGGER_ATTRIBUTES) { "Custom trigger has too many attributes" }
            require(attributes.keys.all { it.isNotBlank() && it.length <= MAX_CONTEXT_IDENTIFIER_LENGTH }) {
                "Custom trigger attribute names must be non-blank and bounded"
            }
            require(attributes.values.all { it.length <= MAX_CUSTOM_TRIGGER_ATTRIBUTE_VALUE_LENGTH }) {
                "Custom trigger attribute value is too long"
            }
        }
    }
}

@Serializable
enum class MobileWorkerPlatform {
    ANDROID,
    IOS,
}

@Serializable
enum class LocationCause {
    CURRENT,
    SIGNIFICANT_CHANGE,
    GEOFENCE,
}

@Serializable
enum class GeofenceTransition {
    ENTERED,
    EXITED,
}

@Serializable
enum class VehicleSystem {
    ANDROID_AUTO,
    CARPLAY,
}

@Serializable
enum class SleepState {
    ASLEEP,
    AWAKE,
}

@Serializable
data class ContextStateEntry(
    val userId: User.Id,
    val subject: ContextEvent.Subject,
    val stateKey: String,
    val eventId: ContextEventId,
    val payload: ContextEvent.Payload,
    val observedAt: Instant,
    val receivedAt: Instant,
)

@Serializable
data class DeviceStateSnapshot(
    val workerId: ConversationRuntimeWorkerId,
    val subjectUserId: User.Id,
    val values: List<ContextStateEntry>,
    val lastEventAt: Instant?,
)

@Serializable
data class UserStateSnapshot(
    val userId: User.Id,
    val activeClient: ContextStateEntry?,
    val declarations: List<ContextStateEntry>,
    val devices: List<DeviceStateSnapshot>,
    val conflicts: List<ContextStateConflict>,
)

@Serializable
data class ContextStateConflict(
    val kind: Kind,
    val subject: ContextEvent.Subject,
    val stateKey: String,
    val eventIds: List<ContextEventId>,
    val description: String,
) {
    @Serializable
    enum class Kind {
        DECLARATION_MISMATCH,
        IMPLAUSIBLE_MOVEMENT,
    }
}

data class DeviceObservation(
    val id: String,
    val observedAt: Instant,
    val payload: DeviceStateEvent,
) {
    init {
        require(id.matches(eventIdPattern)) {
            "Mobile event ID must contain 1-128 letters, digits, dots, dashes, or underscores"
        }
    }
}

data class ContextEventAppendResult(
    val acceptedEventIds: Set<ContextEventId>,
    val duplicateEventIds: Set<ContextEventId>,
)

fun ContextEvent.projectionKey(): String? =
    when (val value = payload) {
        is ContextEvent.Payload.ActiveClient -> "active_client"
        is ContextEvent.Payload.UserDeclaration -> "declaration:${value.stateKey}"
        is ContextEvent.Payload.Device -> value.event.projectionKey()
    }

fun DeviceStateEvent.projectionKey(): String? =
    when (this) {
        is DeviceStateEvent.DeviceInfo -> "device_info"
        is DeviceStateEvent.Battery -> "battery"
        is DeviceStateEvent.Location -> "location"
        is DeviceStateEvent.Geofence -> "geofence:${regionId.toStateKeyPart()}"
        is DeviceStateEvent.BlePresence -> "ble:${deviceId.toStateKeyPart()}"
        is DeviceStateEvent.VehicleConnection -> "vehicle:${system.name.lowercase()}"
        is DeviceStateEvent.VehicleAudioConnection -> "vehicle_audio_connected"
        is DeviceStateEvent.Sleep -> "sleep"
        is DeviceStateEvent.WifiConnection -> "wifi:${networkId.toStateKeyPart()}"
        is DeviceStateEvent.BluetoothPower -> "bluetooth"
        is DeviceStateEvent.AirplaneMode -> "airplane_mode"
        is DeviceStateEvent.NfcTag,
        is DeviceStateEvent.CustomTrigger -> null
    }

private fun String.toStateKeyPart(): String =
    encodeToByteArray().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private val eventIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val stateKeyPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private const val MAX_CONTEXT_IDENTIFIER_LENGTH = 128
private const val MAX_CONTEXT_LABEL_LENGTH = 255
private const val MAX_CUSTOM_TRIGGER_ATTRIBUTES = 32
private const val MAX_CUSTOM_TRIGGER_ATTRIBUTE_VALUE_LENGTH = 1_024
