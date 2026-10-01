package com.gromozeka.mobile.worker

import com.gromozeka.worker.runtime.WorkerRegistrationClient
import com.gromozeka.worker.runtime.WorkerEventClient
import com.gromozeka.worker.runtime.WorkerEventOutbox
import com.gromozeka.worker.runtime.WorkerEventOutboxState
import com.gromozeka.worker.runtime.WorkerEventOutboxStore
import com.gromozeka.worker.runtime.WorkerEventOutboxReplacedException
import com.gromozeka.worker.runtime.WorkerEventBatchSender
import com.gromozeka.worker.runtime.WorkerEventOutboxFullException
import com.gromozeka.worker.runtime.WorkerEventOutboxLimits
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.CancellationException
import com.gromozeka.worker.runtime.appendWorkerEventBatch
import com.gromozeka.domain.model.DeviceCollectionSource
import com.gromozeka.domain.model.DeviceCollectionState
import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.domain.model.GeofenceTransition
import com.gromozeka.domain.model.LocationCause
import com.gromozeka.worker.runtime.WorkerLocationConfiguration
import com.gromozeka.worker.runtime.WorkerLocationSample
import com.gromozeka.domain.model.WorkerAppState
import com.gromozeka.domain.model.WorkerPlatform
import com.gromozeka.domain.model.SleepState
import com.gromozeka.domain.model.VehicleSystem
import com.gromozeka.domain.model.DeviceConnection
import com.gromozeka.domain.model.projectionKey
import com.gromozeka.remote.protocol.WorkerEventBatchRequest
import com.gromozeka.remote.protocol.WorkerEventInput
import com.gromozeka.remote.protocol.WorkerContactMetadata
import com.gromozeka.shared.logging.GromozekaLogging
import com.gromozeka.remote.protocol.WorkerEnrollmentBootstrap
import com.gromozeka.remote.protocol.WorkerEnrollmentConsumeRequest
import com.gromozeka.remote.protocol.DeviceConnectionConsumeResponse
import com.gromozeka.remote.protocol.DeviceConnectionPasswordRequest
import com.gromozeka.remote.protocol.DeviceConnectionStartRequest
import com.gromozeka.remote.protocol.DeviceConnectionWorkerRequest
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class MobileWorkerRuntime(
    private val storage: MobileWorkerStorage,
    private val platform: WorkerPlatform,
    private val deviceName: String,
    private val operatingSystemVersion: String,
    private val appVersion: String,
    private val httpClient: HttpClient = createMobileWorkerHttpClient(),
    private val onEventsQueued: () -> Unit = {},
    private val outboxLimits: WorkerEventOutboxLimits = WorkerEventOutboxLimits(),
) {
    private val registrationClient = WorkerRegistrationClient(httpClient)
    private val log = GromozekaLogging.logger("MobileWorkerRuntime")

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
        val bootstrap = registrationClient.enroll(
            baseUrl,
            WorkerEnrollmentConsumeRequest(
                token = enrollmentToken,
                workerId = workerId,
                platform = platform.name.lowercase(),
                bindToUser = true,
            ),
        )
        require(bootstrap.subjectUserId != null) {
            "Server did not bind the Worker to a user for context reporting"
        }
        persistEnrollment(baseUrl, bootstrap).also {
            log.info { "Enrollment completed platform=${platform.name}" }
        }
    }

    suspend fun startDeviceConnection(
        serverUrl: String,
        workerId: String,
    ): MobileWorkerConnectionChallenge = mobileWorkerStorageMutex.withLock {
        check(!readState().enrolled) {
            "Mobile Worker is already enrolled; remove the existing enrollment first"
        }
        val baseUrl = normalizeServerUrl(serverUrl)
        val response = registrationClient.start(
            serverUrl = baseUrl,
            request = DeviceConnectionStartRequest(
                deviceLabel = deviceName,
                platform = platform.name.lowercase(),
                components = setOf(DeviceConnection.Component.WORKER),
                worker = DeviceConnectionWorkerRequest(
                    workerId = workerId,
                    bindToUser = true,
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
            registrationClient.consume(baseUrl, deviceToken),
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
            registrationClient.authenticate(
                serverUrl = baseUrl,
                request = DeviceConnectionPasswordRequest(
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
        require(bootstrap.subjectUserId != null) {
            "Server did not bind the Worker to a user for context reporting"
        }
        storage.writeCredential(bootstrap.gatewayCredential)
        check(storage.readCredential() == bootstrap.gatewayCredential) {
            "Mobile Worker credential could not be persisted"
        }
        val deviceInfo = WorkerEventInput(
            id = uuid7(),
            observedAt = Clock.System.now(),
            payload = currentDeviceInfo(),
        )
        val state = PersistedMobileWorkerState(
            serverUrl = baseUrl,
            workerId = bootstrap.workerId,
            outbox = WorkerEventOutboxState(
                streamId = uuid7(),
                pending = listOf(deviceInfo),
                latest = mapOf(requireNotNull(deviceInfo.payload.projectionKey()) to deviceInfo),
            ),
        )
        writeState(state)
        onEventsQueued()
        return state.toStatus(hasCredential = true)
    }

    suspend fun status(): MobileWorkerStatus = mobileWorkerStorageMutex.withLock {
        readState().toStatus(storage.readCredential() != null)
    }

    suspend fun setGatewayEnabled(enabled: Boolean) = mobileWorkerStorageMutex.withLock {
        val state = readState()
        check(state.enrolled || !enabled) { "Worker must be enrolled before enabling remote commands" }
        writeState(state.copy(gatewayEnabled = enabled))
    }

    suspend fun setSoundEnabled(enabled: Boolean) = mobileWorkerStorageMutex.withLock {
        val state = readState()
        check(state.enrolled || !enabled) { "Worker must be enrolled before enabling loud sound" }
        writeState(state.copy(soundEnabled = enabled))
    }

    suspend fun configureLocation(configuration: WorkerLocationConfiguration) = mobileWorkerStorageMutex.withLock {
        val state = readState()
        check(state.enrolled || !configuration.enabled) { "Worker must be enrolled before sharing location" }
        if (state.locationConfiguration != configuration) {
            writeState(state.copy(locationConfiguration = configuration, locationRevision = uuid7())
                .queueCollectionControls(listOf(DeviceStateEvent.CollectionStatus(DeviceCollectionSource.LOCATION,
                    if (configuration.enabled) DeviceCollectionState.STARTING else DeviceCollectionState.DISABLED)), Clock.System.now()))
            onEventsQueued()
        }
    }

    suspend fun locationCollection(): MobileWorkerLocationCollection? = mobileWorkerStorageMutex.withLock {
        readState().locationCollection()
    }

    suspend fun recordSharedLocation(collection: MobileWorkerLocationCollection, sample: WorkerLocationSample) {
        eventOutbox(collection.streamId, collection).append(listOf(
            WorkerEventInput(uuid7(), sample.observedAt, sample.location),
            WorkerEventInput(uuid7(), Clock.System.now(), DeviceStateEvent.CollectionStatus(DeviceCollectionSource.LOCATION, DeviceCollectionState.ACTIVE)),
        ))
    }

    suspend fun configureTelemetry(
        configuration: WorkerTelemetryConfiguration,
        enabledAt: Instant = Clock.System.now(),
    ) = mobileWorkerStorageMutex.withLock {
        val state = readState()
        check(state.enrolled || !configuration.enabled) { "Worker must be enrolled before collecting telemetry" }
        if (state.telemetryConfiguration != configuration) {
            val preserveCursor = configuration.enabled && state.telemetryConfiguration.enabled &&
                configuration.applicationUsageEnabled == state.telemetryConfiguration.applicationUsageEnabled
            writeState(state.copy(
                telemetryConfiguration = configuration,
                telemetryRevision = uuid7(),
                telemetryCheckpoint = if (preserveCursor) state.telemetryCheckpoint else
                    WorkerTelemetryCheckpoint(enabledAt, enabledAt, uuid7()),
            ).queueCollectionControls(listOf(
                DeviceStateEvent.CollectionStatus(DeviceCollectionSource.DEVICE,
                    if (configuration.enabled) DeviceCollectionState.STARTING else DeviceCollectionState.DISABLED),
                DeviceStateEvent.CollectionStatus(DeviceCollectionSource.APP_USAGE,
                    if (configuration.enabled && configuration.applicationUsageEnabled) DeviceCollectionState.STARTING else DeviceCollectionState.DISABLED),
            ), enabledAt))
            onEventsQueued()
        }
    }

    suspend fun telemetryCollection(): MobileWorkerTelemetryCollection? = mobileWorkerStorageMutex.withLock {
        readState().telemetryCollection()
    }

    /** Events and their read cursor commit in one encrypted atomic-file replacement. */
    suspend fun recordTelemetry(
        collection: MobileWorkerTelemetryCollection,
        events: List<WorkerEventInput>,
        checkpoint: WorkerTelemetryCheckpoint,
    ) {
        eventOutbox(collection.streamId, telemetryCommit = collection to checkpoint).append(events)
    }

    suspend fun gatewayEnrollment(): MobileWorkerGatewayEnrollment? = mobileWorkerStorageMutex.withLock {
        val state = readState()
        if (!state.enrolled || !state.gatewayEnabled) return@withLock null
        MobileWorkerGatewayEnrollment(
            serverUrl = requireNotNull(state.serverUrl),
            workerId = requireNotNull(state.workerId),
            streamId = requireNotNull(state.outbox).streamId,
            credential = requireNotNull(storage.readCredential()) { "Worker credential is missing" },
        )
    }

    suspend fun synchronize(
        appState: WorkerAppState = WorkerAppState.UNKNOWN,
        heartbeatWhenIdle: Boolean = false,
    ): MobileWorkerStatus {
        val session = mobileWorkerStorageMutex.withLock {
            val state = readState()
            if (!state.enrolled) return state.toStatus(storage.readCredential() != null)
            EventSession(state, requireNotNull(storage.readCredential()) { "Worker credential is missing" })
        }
        val streamId = requireNotNull(session.state.outbox).streamId
        val outbox = eventOutbox(streamId)
        try {
            flushCollectionControls(streamId)
            recordCurrentDeviceInfo(outbox)
            val client = WorkerEventClient(httpClient, requireNotNull(session.state.serverUrl), session.credential)
            try {
                val sentCount = outbox.synchronize(WorkerEventBatchSender { batch, pendingCount ->
                    client.send(WorkerEventBatchRequest(
                        events = batch,
                        contact = contactMetadata(uuid7(), appState, pendingCount),
                    ))
                })
                flushCollectionControls(streamId)
                recordCurrentDeviceInfo(outbox)
                if (sentCount == 0 && heartbeatWhenIdle) {
                    val pending = status().pendingEventCount
                    val response = client.heartbeat(contactMetadata(uuid7(), appState, pending))
                    mobileWorkerStorageMutex.withLock {
                        val current = readState()
                        val currentOutbox = requireNotNull(current.outbox)
                        if (currentOutbox.streamId != streamId) throw WorkerEventOutboxReplacedException()
                        writeState(current.copy(outbox = currentOutbox.copy(lastAcknowledgedAt = response.serverReceivedAt)))
                    }
                }
                log.info { "Synchronization acknowledged events=$sentCount appState=${appState.name}" }
                return status()
            } finally { client.close() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.warn { "Synchronization failed appState=${appState.name} error=${error::class.simpleName}" }
            throw error
        }
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
    ) {
        require(endedAt >= startedAt) { "Sleep session must not end before it starts" }
        val sessionId = "sleep-${startedAt.toEpochMilliseconds()}-${endedAt.toEpochMilliseconds()}"
        currentOutbox().append(listOf(
            WorkerEventInput("${sessionId}-asleep", startedAt, DeviceStateEvent.Sleep(SleepState.ASLEEP)),
            WorkerEventInput("${sessionId}-awake", endedAt, DeviceStateEvent.Sleep(SleepState.AWAKE)),
        ), suppressUnchanged = false)
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

    private suspend fun enqueue(payload: DeviceStateEvent, observedAt: Instant) {
        currentOutbox().append(listOf(WorkerEventInput(uuid7(), observedAt, payload)))
        log.debug { "Queued event type=${payload::class.simpleName}" }
    }

    private suspend fun currentOutbox(): WorkerEventOutbox = mobileWorkerStorageMutex.withLock {
        val state = readState()
        check(state.enrolled) { "Worker must be enrolled before recording events" }
        eventOutbox(requireNotNull(state.outbox).streamId)
    }

    private fun eventOutbox(
        streamId: String,
        locationCollection: MobileWorkerLocationCollection? = null,
        telemetryCommit: Pair<MobileWorkerTelemetryCollection, WorkerTelemetryCheckpoint>? = null,
    ) = WorkerEventOutbox(
        streamId = streamId,
        limits = outboxLimits,
        synchronization = mobileWorkerSynchronizationMutex,
        store = object : WorkerEventOutboxStore {
            override suspend fun read(): WorkerEventOutboxState = mobileWorkerStorageMutex.withLock {
                readState().outbox ?: throw WorkerEventOutboxReplacedException()
            }

            override suspend fun update(transform: (WorkerEventOutboxState) -> WorkerEventOutboxState): WorkerEventOutboxState =
                mobileWorkerStorageMutex.withLock {
                    val state = readState()
                    if (locationCollection != null) {
                        check(state.locationCollection() == locationCollection) { "Location sharing changed or was disabled" }
                    }
                    if (telemetryCommit != null) {
                        check(state.telemetryCollection() == telemetryCommit.first) {
                            "Telemetry configuration, enrollment or read cursor changed"
                        }
                    }
                    val outbox = state.outbox ?: throw WorkerEventOutboxReplacedException()
                    val updated = transform(outbox)
                    if (updated != outbox || telemetryCommit != null) {
                        writeState(state.copy(outbox = updated,
                            telemetryCheckpoint = telemetryCommit?.second ?: state.telemetryCheckpoint))
                        if (outbox.pending.isEmpty() && updated.pending.isNotEmpty()) onEventsQueued()
                    }
                    updated
                }
        },
    )

    private suspend fun flushCollectionControls(streamId: String) = mobileWorkerStorageMutex.withLock {
        val state = readState()
        val outbox = state.outbox ?: throw WorkerEventOutboxReplacedException()
        if (outbox.streamId != streamId) throw WorkerEventOutboxReplacedException()
        if (state.pendingCollectionControls.isNotEmpty()) {
            var updated = outbox
            var appended = 0
            for (event in state.pendingCollectionControls) {
                try {
                    updated = appendWorkerEventBatch(updated, listOf(event), outboxLimits)
                    appended++
                } catch (_: WorkerEventOutboxFullException) { break }
            }
            // Disabling remains possible even with a full queue. At most three desired
            // states wait in their own bounded durable slot and drain as space becomes available.
            if (appended > 0) writeState(state.copy(outbox = updated,
                pendingCollectionControls = state.pendingCollectionControls.drop(appended)))
        }
    }

    private data class EventSession(val state: PersistedMobileWorkerState, val credential: String)

    private suspend fun recordCurrentDeviceInfo(outbox: WorkerEventOutbox) {
        try {
            outbox.append(listOf(WorkerEventInput(uuid7(), Clock.System.now(), currentDeviceInfo())))
        } catch (error: WorkerEventOutboxFullException) {
            log.warn { "Device information update deferred until the event backlog drains" }
        }
    }

    private fun currentDeviceInfo(): DeviceStateEvent.DeviceInfo =
        DeviceStateEvent.DeviceInfo(
            platform = platform,
            deviceName = deviceName,
            operatingSystemVersion = operatingSystemVersion,
            appVersion = appVersion,
        )

    private fun contactMetadata(
        requestId: String,
        appState: WorkerAppState,
        pendingEventCount: Int,
    ): WorkerContactMetadata =
        WorkerContactMetadata(
            requestId = requestId,
            sentAt = Clock.System.now(),
            appState = appState,
            appVersion = appVersion,
            pendingEventCount = pendingEventCount,
        )

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

}

@Serializable
data class MobileWorkerStatus(
    val enrolled: Boolean,
    val serverUrl: String?,
    val workerId: String?,
    val pendingEventCount: Int,
    val lastSynchronizedAt: Instant?,
    val credentialAvailable: Boolean,
    val gatewayEnabled: Boolean = false,
    val soundEnabled: Boolean = false,
    val locationConfiguration: WorkerLocationConfiguration = WorkerLocationConfiguration(),
    val lastLocation: WorkerLocationSample? = null,
    val telemetryConfiguration: WorkerTelemetryConfiguration = WorkerTelemetryConfiguration(),
    val lastTelemetryCollectedAt: Instant? = null,
    val usageQueriedThrough: Instant? = null,
)

internal data class MobileWorkerLocationCollection(
    val streamId: String,
    val revision: String,
    val configuration: WorkerLocationConfiguration,
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
    val outbox: WorkerEventOutboxState? = null,
    val gatewayEnabled: Boolean = false,
    val soundEnabled: Boolean = false,
    val locationConfiguration: WorkerLocationConfiguration = WorkerLocationConfiguration(),
    val locationRevision: String = "initial",
    val telemetryConfiguration: WorkerTelemetryConfiguration = WorkerTelemetryConfiguration(),
    val telemetryRevision: String = "initial",
    val telemetryCheckpoint: WorkerTelemetryCheckpoint? = null,
    val pendingCollectionControls: List<WorkerEventInput> = emptyList(),
) {
    fun queueCollectionControls(controls: List<DeviceStateEvent.CollectionStatus>, observedAt: Instant): PersistedMobileWorkerState {
        val sources = controls.map { it.source }.toSet()
        val pending = pendingCollectionControls.filterNot { (it.payload as? DeviceStateEvent.CollectionStatus)?.source in sources }
        return copy(pendingCollectionControls = pending + controls.map { WorkerEventInput(uuid7(), observedAt, it) })
    }

    val enrolled: Boolean
        get() = !serverUrl.isNullOrBlank() && !workerId.isNullOrBlank() && outbox != null

    fun locationCollection(): MobileWorkerLocationCollection? =
        if (enrolled && locationConfiguration.enabled) MobileWorkerLocationCollection(requireNotNull(outbox).streamId, locationRevision, locationConfiguration)
        else null

    fun telemetryCollection(): MobileWorkerTelemetryCollection? =
        if (enrolled && telemetryConfiguration.enabled && telemetryCheckpoint != null)
            MobileWorkerTelemetryCollection(requireNotNull(outbox).streamId, telemetryRevision, telemetryConfiguration, telemetryCheckpoint)
        else null

    fun toStatus(hasCredential: Boolean): MobileWorkerStatus =
        MobileWorkerStatus(
            enrolled = enrolled,
            serverUrl = serverUrl,
            workerId = workerId,
            pendingEventCount = (outbox?.pending?.size ?: 0) + pendingCollectionControls.size,
            lastSynchronizedAt = outbox?.lastAcknowledgedAt,
            credentialAvailable = hasCredential,
            gatewayEnabled = gatewayEnabled,
            soundEnabled = soundEnabled,
            telemetryConfiguration = telemetryConfiguration,
            lastTelemetryCollectedAt = telemetryCheckpoint?.lastCollectedAt,
            usageQueriedThrough = telemetryCheckpoint?.takeIf { it.usageAvailable == true }?.usageThrough,
            locationConfiguration = locationConfiguration,
            lastLocation = outbox?.latest?.get("location")?.let {
                WorkerLocationSample(it.observedAt, it.payload as DeviceStateEvent.Location)
            },
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
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = MOBILE_WORKER_CONNECT_TIMEOUT_MILLIS
        requestTimeoutMillis = MOBILE_WORKER_REQUEST_TIMEOUT_MILLIS
        socketTimeoutMillis = MOBILE_WORKER_REQUEST_TIMEOUT_MILLIS
    }
    install(ContentNegotiation) {
        json(mobileWorkerJson)
    }
}

private val mobileWorkerJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

private val mobileWorkerStorageMutex = Mutex()
private val mobileWorkerSynchronizationMutex = Mutex()
private const val MOBILE_WORKER_CONNECT_TIMEOUT_MILLIS = 10_000L
private const val MOBILE_WORKER_REQUEST_TIMEOUT_MILLIS = 20_000L
