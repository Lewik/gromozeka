package com.gromozeka.mobile.worker

import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.domain.model.GeofenceTransition
import com.gromozeka.domain.model.LocationCause
import com.gromozeka.domain.model.MobileWorkerPlatform
import com.gromozeka.domain.model.SleepState
import com.gromozeka.domain.model.VehicleSystem
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.model.DeviceConnection
import com.gromozeka.domain.model.projectionKey
import com.gromozeka.remote.protocol.MobileWorkerEventBatchRequest
import com.gromozeka.remote.protocol.MobileWorkerEventBatchResponse
import com.gromozeka.remote.protocol.MobileWorkerEventInput
import com.gromozeka.remote.protocol.WorkerEnrollmentBootstrap
import com.gromozeka.remote.protocol.WorkerEnrollmentConsumeRequest
import com.gromozeka.remote.protocol.DeviceConnectionChallenge
import com.gromozeka.remote.protocol.DeviceConnectionConsumeRequest
import com.gromozeka.remote.protocol.DeviceConnectionConsumeResponse
import com.gromozeka.remote.protocol.DeviceConnectionPasswordRequest
import com.gromozeka.remote.protocol.DeviceConnectionStartRequest
import com.gromozeka.remote.protocol.DeviceConnectionWorkerRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class MobileWorkerRuntime(
    private val storage: MobileWorkerStorage,
    private val platform: MobileWorkerPlatform,
    private val deviceName: String,
    private val operatingSystemVersion: String,
    private val appVersion: String,
    private val httpClient: HttpClient = createMobileWorkerHttpClient(),
) {
    init {
        require(deviceName.isNotBlank()) { "Device name must not be blank" }
        require(operatingSystemVersion.isNotBlank()) { "Operating system version must not be blank" }
        require(appVersion.isNotBlank()) { "Mobile Worker version must not be blank" }
    }

    suspend fun enroll(
        serverUrl: String,
        enrollmentToken: String,
        workerId: String,
    ): MobileWorkerStatus = mobileWorkerStorageMutex.withLock {
        check(!readState().enrolled) {
            "Mobile Worker is already enrolled; remove the existing enrollment first"
        }
        val baseUrl = normalizeServerUrl(serverUrl)
        val response = httpClient.post("$baseUrl/api/worker-enrollments/consume") {
            contentType(ContentType.Application.Json)
            setBody(
                WorkerEnrollmentConsumeRequest(
                    token = enrollmentToken,
                    workerId = workerId,
                    kind = WorkerResource.Kind.MOBILE_DEVICE,
                )
            )
        }
        if (!response.status.isSuccess()) {
            error(response.mobileWorkerError("Enrollment failed"))
        }
        val bootstrap = response.body<WorkerEnrollmentBootstrap>()
        require(bootstrap.kind == WorkerResource.Kind.MOBILE_DEVICE) {
            "Server enrolled an unexpected Worker kind"
        }
        persistEnrollment(baseUrl, bootstrap)
    }

    suspend fun startDeviceConnection(
        serverUrl: String,
        workerId: String,
    ): MobileWorkerConnectionChallenge = mobileWorkerStorageMutex.withLock {
        check(!readState().enrolled) {
            "Mobile Worker is already enrolled; remove the existing enrollment first"
        }
        val baseUrl = normalizeServerUrl(serverUrl)
        val response = postDeviceConnection<DeviceConnectionStartRequest, DeviceConnectionChallenge>(
            baseUrl = baseUrl,
            path = "/auth/device-connections",
            payload = DeviceConnectionStartRequest(
                deviceLabel = deviceName,
                platform = platform.name.lowercase(),
                components = setOf(DeviceConnection.Component.WORKER),
                worker = DeviceConnectionWorkerRequest(
                    workerId = workerId,
                    kind = WorkerResource.Kind.MOBILE_DEVICE,
                ),
            ),
        )
        MobileWorkerConnectionChallenge(
            deviceToken = response.deviceToken,
            userCode = response.userCode,
            verificationUrl = baseUrl + response.verificationPathComplete,
            expiresAt = response.expiresAt,
            pollIntervalSeconds = response.pollIntervalSeconds,
        )
    }

    suspend fun consumeDeviceConnection(
        serverUrl: String,
        deviceToken: String,
    ): MobileWorkerConnectionResult = mobileWorkerStorageMutex.withLock {
        check(!readState().enrolled) {
            "Mobile Worker is already enrolled; remove the existing enrollment first"
        }
        val baseUrl = normalizeServerUrl(serverUrl)
        completeDeviceConnection(
            baseUrl,
            postDeviceConnection<DeviceConnectionConsumeRequest, DeviceConnectionConsumeResponse>(
                baseUrl = baseUrl,
                path = "/auth/device-connections/consume",
                payload = DeviceConnectionConsumeRequest(deviceToken),
            ),
        )
    }

    suspend fun connectWithPassword(
        serverUrl: String,
        deviceToken: String,
        username: String,
        password: String,
    ): MobileWorkerConnectionResult = mobileWorkerStorageMutex.withLock {
        check(!readState().enrolled) {
            "Mobile Worker is already enrolled; remove the existing enrollment first"
        }
        val baseUrl = normalizeServerUrl(serverUrl)
        completeDeviceConnection(
            baseUrl,
            postDeviceConnection<DeviceConnectionPasswordRequest, DeviceConnectionConsumeResponse>(
                baseUrl = baseUrl,
                path = "/auth/device-connections/password",
                payload = DeviceConnectionPasswordRequest(
                    deviceToken = deviceToken,
                    username = username,
                    password = password,
                ),
            ),
        )
    }

    private fun completeDeviceConnection(
        baseUrl: String,
        response: DeviceConnectionConsumeResponse,
    ): MobileWorkerConnectionResult = when (response.status) {
        DeviceConnectionConsumeResponse.Status.PENDING -> MobileWorkerConnectionResult(
            status = MobileWorkerConnectionStatus.PENDING,
            retryAfterSeconds = response.retryAfterSeconds,
        )
        DeviceConnectionConsumeResponse.Status.CONNECTED -> {
            val bootstrap = requireNotNull(response.worker) {
                "Connected Mobile Worker response has no Worker credential"
            }
            MobileWorkerConnectionResult(
                status = MobileWorkerConnectionStatus.CONNECTED,
                workerStatus = persistEnrollment(baseUrl, bootstrap),
            )
        }
        DeviceConnectionConsumeResponse.Status.DENIED -> MobileWorkerConnectionResult(
            status = MobileWorkerConnectionStatus.DENIED,
            message = response.message,
        )
        DeviceConnectionConsumeResponse.Status.EXPIRED -> MobileWorkerConnectionResult(
            status = MobileWorkerConnectionStatus.EXPIRED,
            message = response.message,
        )
    }

    private fun persistEnrollment(
        baseUrl: String,
        bootstrap: WorkerEnrollmentBootstrap,
    ): MobileWorkerStatus {
        require(bootstrap.kind == WorkerResource.Kind.MOBILE_DEVICE) {
            "Server enrolled an unexpected Worker kind"
        }
        storage.writeCredential(bootstrap.gatewayCredential)
        check(storage.readCredential() == bootstrap.gatewayCredential) {
            "Mobile Worker credential could not be persisted"
        }
        val deviceInfo = MobileWorkerEventInput(
            id = randomMobileWorkerEventId(),
            observedAt = Clock.System.now(),
            payload = DeviceStateEvent.DeviceInfo(
                platform = platform,
                deviceName = deviceName,
                operatingSystemVersion = operatingSystemVersion,
                appVersion = appVersion,
            ),
        )
        val state = PersistedMobileWorkerState(
            serverUrl = baseUrl,
            workerId = bootstrap.workerId,
            pendingEvents = listOf(deviceInfo),
            lastRecordedValues = deviceInfo.payload.projectionKey()
                ?.let { mapOf(it to deviceInfo.payload) }
                ?: emptyMap(),
        )
        writeState(state)
        return state.toStatus(hasCredential = true)
    }

    private suspend inline fun <reified TRequest, reified TResponse> postDeviceConnection(
        baseUrl: String,
        path: String,
        payload: TRequest,
    ): TResponse {
        val response = httpClient.post("$baseUrl$path") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) {
            error(response.mobileWorkerError("Device connection failed"))
        }
        return response.body()
    }

    suspend fun status(): MobileWorkerStatus = mobileWorkerStorageMutex.withLock {
        readState().toStatus(storage.readCredential() != null)
    }

    suspend fun synchronize(): MobileWorkerStatus = mobileWorkerStorageMutex.withLock {
        synchronizeLocked(readState())
    }

    suspend fun reset() = mobileWorkerStorageMutex.withLock {
        storage.writeState(mobileWorkerJson.encodeToString(PersistedMobileWorkerState()))
        storage.clearCredential()
        check(storage.readCredential() == null) { "Mobile Worker credential could not be removed" }
    }

    suspend fun recordBattery(
        levelPercent: Int,
        charging: Boolean,
        lowPowerMode: Boolean? = null,
        observedAt: Instant = Clock.System.now(),
    ) = enqueue(DeviceStateEvent.Battery(levelPercent, charging, lowPowerMode), observedAt)

    suspend fun recordLocation(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double?,
        altitudeMeters: Double? = null,
        speedMetersPerSecond: Double? = null,
        cause: LocationCause = LocationCause.SIGNIFICANT_CHANGE,
        observedAt: Instant = Clock.System.now(),
    ) = enqueue(
        DeviceStateEvent.Location(
            latitude,
            longitude,
            accuracyMeters,
            altitudeMeters,
            speedMetersPerSecond,
            cause,
        ),
        observedAt,
    )

    suspend fun recordGeofence(
        regionId: String,
        transition: GeofenceTransition,
        observedAt: Instant = Clock.System.now(),
    ) = enqueue(DeviceStateEvent.Geofence(regionId, transition), observedAt)

    suspend fun recordBlePresence(
        deviceId: String,
        displayName: String?,
        present: Boolean,
        observedAt: Instant = Clock.System.now(),
    ) = enqueue(DeviceStateEvent.BlePresence(deviceId, displayName, present), observedAt)

    suspend fun recordVehicleConnection(
        system: VehicleSystem,
        connected: Boolean,
        observedAt: Instant = Clock.System.now(),
    ) = enqueue(DeviceStateEvent.VehicleConnection(system, connected), observedAt)

    suspend fun recordVehicleAudioConnection(
        connected: Boolean,
        observedAt: Instant = Clock.System.now(),
    ) = enqueue(DeviceStateEvent.VehicleAudioConnection(connected), observedAt)

    suspend fun recordSleep(
        state: SleepState,
        observedAt: Instant = Clock.System.now(),
    ) = enqueue(DeviceStateEvent.Sleep(state), observedAt)

    suspend fun recordCompletedSleepSession(
        startedAt: Instant,
        endedAt: Instant,
    ) = mobileWorkerStorageMutex.withLock {
        require(endedAt >= startedAt) { "Sleep session must not end before it starts" }
        val state = readState()
        check(state.enrolled) { "Mobile Worker must be enrolled before recording events" }
        val sessionId = "sleep-${startedAt.toEpochMilliseconds()}-${endedAt.toEpochMilliseconds()}"
        val awake = DeviceStateEvent.Sleep(SleepState.AWAKE)
        val pendingIds = state.pendingEvents.mapTo(hashSetOf()) { it.id }
        val events = listOf(
            MobileWorkerEventInput(
                id = "$sessionId-asleep",
                observedAt = startedAt,
                payload = DeviceStateEvent.Sleep(SleepState.ASLEEP),
            ),
            MobileWorkerEventInput(
                id = "$sessionId-awake",
                observedAt = endedAt,
                payload = awake,
            ),
        ).filterNot { it.id in pendingIds }
        if (events.isEmpty()) return@withLock
        writeState(
            state.copy(
                pendingEvents = state.pendingEvents + events,
                lastRecordedValues = state.lastRecordedValues +
                    (awake.projectionKey()!! to awake),
            )
        )
    }

    suspend fun recordWifiConnection(
        networkId: String,
        connected: Boolean,
        observedAt: Instant = Clock.System.now(),
    ) = enqueue(DeviceStateEvent.WifiConnection(networkId, connected), observedAt)

    suspend fun recordBluetoothPower(
        enabled: Boolean,
        observedAt: Instant = Clock.System.now(),
    ) = enqueue(DeviceStateEvent.BluetoothPower(enabled), observedAt)

    suspend fun recordNfcTag(
        tagId: String,
        observedAt: Instant = Clock.System.now(),
    ) = enqueue(DeviceStateEvent.NfcTag(tagId), observedAt)

    suspend fun recordAirplaneMode(
        enabled: Boolean,
        observedAt: Instant = Clock.System.now(),
    ) = enqueue(DeviceStateEvent.AirplaneMode(enabled), observedAt)

    suspend fun recordCustomTrigger(
        name: String,
        attributes: Map<String, String> = emptyMap(),
        observedAt: Instant = Clock.System.now(),
    ) = enqueue(DeviceStateEvent.CustomTrigger(name, attributes), observedAt)

    private suspend fun enqueue(payload: DeviceStateEvent, observedAt: Instant) = mobileWorkerStorageMutex.withLock {
        val state = readState()
        check(state.enrolled) { "Mobile Worker must be enrolled before recording events" }
        val projectionKey = payload.projectionKey()
        if (projectionKey != null && state.lastRecordedValues[projectionKey] == payload) return@withLock
        writeState(
            state.copy(
                pendingEvents = state.pendingEvents + MobileWorkerEventInput(
                    id = randomMobileWorkerEventId(),
                    observedAt = observedAt,
                    payload = payload,
                ),
                lastRecordedValues = projectionKey
                    ?.let { state.lastRecordedValues + (it to payload) }
                    ?: state.lastRecordedValues,
            )
        )
    }

    private suspend fun synchronizeLocked(initialState: PersistedMobileWorkerState): MobileWorkerStatus {
        var state = initialState
        if (!state.enrolled) return state.toStatus(storage.readCredential() != null)
        val credential = storage.readCredential()
            ?: error("Mobile Worker credential is missing; enroll the device again")
        while (state.pendingEvents.isNotEmpty()) {
            val batch = state.pendingEvents.take(MAX_SYNC_BATCH_SIZE)
            val response = httpClient.post("${state.serverUrl}/api/mobile-worker/events") {
                header("Authorization", "Bearer $credential")
                contentType(ContentType.Application.Json)
                setBody(MobileWorkerEventBatchRequest(batch))
            }
            if (!response.status.isSuccess()) {
                error(response.mobileWorkerError("Synchronization failed"))
            }
            val acknowledgement = response.body<MobileWorkerEventBatchResponse>()
            val acknowledgedIds = acknowledgement.acceptedEventIds + acknowledgement.duplicateEventIds
            val sentIds = batch.mapTo(linkedSetOf()) { it.id }
            require(acknowledgedIds == sentIds) {
                "Server acknowledgement does not match the submitted Mobile Worker batch"
            }
            state = state.copy(
                pendingEvents = state.pendingEvents.drop(batch.size),
                lastSynchronizedAt = acknowledgement.serverReceivedAt,
            )
            writeState(state)
        }
        return state.toStatus(hasCredential = true)
    }

    private fun readState(): PersistedMobileWorkerState =
        storage.readState()
            ?.takeIf(String::isNotBlank)
            ?.let { mobileWorkerJson.decodeFromString<PersistedMobileWorkerState>(it) }
            ?: PersistedMobileWorkerState()

    private fun writeState(state: PersistedMobileWorkerState) {
        val encoded = mobileWorkerJson.encodeToString(state)
        storage.writeState(encoded)
        check(storage.readState() == encoded) { "Mobile Worker state could not be persisted" }
    }

    fun close() {
        httpClient.close()
    }

    private suspend fun io.ktor.client.statement.HttpResponse.mobileWorkerError(fallback: String): String =
        runCatching {
            mobileWorkerJson.parseToJsonElement(bodyAsText())
                .jsonObject["error"]
                ?.jsonPrimitive
                ?.content
        }.getOrNull() ?: "$fallback with HTTP ${status.value}"
}

@Serializable
data class MobileWorkerStatus(
    val enrolled: Boolean,
    val serverUrl: String?,
    val workerId: String?,
    val pendingEventCount: Int,
    val lastSynchronizedAt: Instant?,
    val credentialAvailable: Boolean,
)

data class MobileWorkerConnectionChallenge(
    val deviceToken: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresAt: Instant,
    val pollIntervalSeconds: Int,
)

data class MobileWorkerConnectionResult(
    val status: MobileWorkerConnectionStatus,
    val workerStatus: MobileWorkerStatus? = null,
    val retryAfterSeconds: Int? = null,
    val message: String? = null,
)

enum class MobileWorkerConnectionStatus {
    PENDING,
    CONNECTED,
    DENIED,
    EXPIRED,
}

@Serializable
private data class PersistedMobileWorkerState(
    val serverUrl: String? = null,
    val workerId: String? = null,
    val pendingEvents: List<MobileWorkerEventInput> = emptyList(),
    val lastRecordedValues: Map<String, DeviceStateEvent> = emptyMap(),
    val lastSynchronizedAt: Instant? = null,
) {
    val enrolled: Boolean
        get() = !serverUrl.isNullOrBlank() && !workerId.isNullOrBlank()

    fun toStatus(hasCredential: Boolean): MobileWorkerStatus =
        MobileWorkerStatus(
            enrolled = enrolled,
            serverUrl = serverUrl,
            workerId = workerId,
            pendingEventCount = pendingEvents.size,
            lastSynchronizedAt = lastSynchronizedAt,
            credentialAvailable = hasCredential,
        )
}

private fun normalizeServerUrl(value: String): String {
    val candidate = value.trim().let {
        when {
            it.startsWith("wss://", ignoreCase = true) -> "https://${it.substringAfter("://")}"
            it.startsWith("ws://", ignoreCase = true) -> "http://${it.substringAfter("://")}"
            "://" !in it -> "https://$it"
            else -> it
        }
    }.removeSuffix("/ws").trimEnd('/')
    val url = Url(candidate)
    require(url.protocol.name == "https") { "Mobile Worker connections require HTTPS" }
    require(url.user == null && url.password == null && url.parameters.isEmpty() && url.fragment.isEmpty()) {
        "Server address must not contain credentials, a query, or a fragment"
    }
    require(url.encodedPath.isEmpty() || url.encodedPath == "/") {
        "Server address must not contain a path"
    }
    return candidate
}

private fun createMobileWorkerHttpClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        connectTimeoutMillis = MOBILE_WORKER_CONNECT_TIMEOUT_MILLIS
        requestTimeoutMillis = MOBILE_WORKER_REQUEST_TIMEOUT_MILLIS
        socketTimeoutMillis = MOBILE_WORKER_REQUEST_TIMEOUT_MILLIS
    }
    install(ContentNegotiation) {
        json(mobileWorkerJson)
    }
}

internal expect fun randomMobileWorkerEventId(): String

private val mobileWorkerJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

private val mobileWorkerStorageMutex = Mutex()
private const val MAX_SYNC_BATCH_SIZE = 100
private const val MOBILE_WORKER_CONNECT_TIMEOUT_MILLIS = 10_000L
private const val MOBILE_WORKER_REQUEST_TIMEOUT_MILLIS = 20_000L
