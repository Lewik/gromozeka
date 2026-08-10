package com.gromozeka.application.service

import com.gromozeka.domain.model.ClientActivity
import com.gromozeka.domain.model.ClientPlatform
import com.gromozeka.domain.model.ContextEvent
import com.gromozeka.domain.model.ContextEventId
import com.gromozeka.domain.model.ContextStateConflict
import com.gromozeka.domain.model.ContextStateEntry
import com.gromozeka.domain.model.DeviceObservation
import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.domain.model.DeviceStateSnapshot
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserStateSnapshot
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.repository.ContextStateRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.shared.uuid.uuid7
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.springframework.stereotype.Service
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Service
class ContextStateApplicationService(
    private val repository: ContextStateRepository,
) {
    suspend fun ingestMobileWorker(
        worker: WorkerResource,
        observations: List<DeviceObservation>,
    ): DeviceObservationAppendResult {
        require(worker.kind == WorkerResource.Kind.MOBILE_DEVICE) {
            "Only a mobile device Worker can submit device observations"
        }
        require(observations.size <= MAX_DEVICE_OBSERVATION_BATCH_SIZE) {
            "Mobile Worker event batch exceeds $MAX_DEVICE_OBSERVATION_BATCH_SIZE events"
        }
        require(observations.map { it.id }.distinct().size == observations.size) {
            "Mobile Worker event batch contains duplicate IDs"
        }
        val subjectUserId = requireNotNull(worker.subjectUserId)
        val receivedAt = Clock.System.now()
        val eventsByLocalId = observations.associate { observation ->
            observation.id to ContextEvent(
                id = mobileEventId(worker.id, observation.id),
                userId = subjectUserId,
                source = ContextEvent.Source.MobileWorker(worker.id),
                subject = ContextEvent.Subject.Device(worker.id),
                payload = ContextEvent.Payload.Device(observation.payload),
                observedAt = observation.observedAt,
                receivedAt = receivedAt,
            )
        }
        val result = repository.append(eventsByLocalId.values.toList())
        val localIdByEventId = eventsByLocalId.entries.associate { (localId, event) -> event.id to localId }
        return DeviceObservationAppendResult(
            acceptedIds = result.acceptedEventIds.mapTo(linkedSetOf(), localIdByEventId::getValue),
            duplicateIds = result.duplicateEventIds.mapTo(linkedSetOf(), localIdByEventId::getValue),
            receivedAt = receivedAt,
        )
    }

    suspend fun recordClientActivity(
        userId: User.Id,
        instanceId: String,
        sessionId: String,
        platform: ClientPlatform,
        activity: ClientActivity,
        active: Boolean,
    ) {
        val now = Clock.System.now()
        repository.append(
            listOf(
                ContextEvent(
                    id = ContextEventId("client:${uuid7()}"),
                    userId = userId,
                    source = ContextEvent.Source.Client(instanceId, sessionId, platform),
                    subject = ContextEvent.Subject.UserState(userId),
                    payload = ContextEvent.Payload.ActiveClient(
                        instanceId = instanceId,
                        sessionId = sessionId,
                        platform = platform,
                        activity = activity,
                        active = active,
                    ),
                    observedAt = now,
                    receivedAt = now,
                )
            )
        )
    }

    suspend fun declareState(
        userId: User.Id,
        subject: ContextEvent.Subject,
        stateKey: String,
        value: JsonElement,
    ) {
        val now = Clock.System.now()
        repository.append(
            listOf(
                ContextEvent(
                    id = ContextEventId("declaration:${uuid7()}"),
                    userId = userId,
                    source = ContextEvent.Source.UserDeclaration(userId),
                    subject = subject,
                    payload = ContextEvent.Payload.UserDeclaration(stateKey, value),
                    observedAt = now,
                    receivedAt = now,
                )
            )
        )
    }

    suspend fun getDeviceState(
        subjectUserId: User.Id,
        workerId: ConversationRuntimeWorkerId,
    ): DeviceStateSnapshot {
        val subject = ContextEvent.Subject.Device(workerId)
        val values = repository.currentState(subjectUserId, subject)
        val lastEventAt = repository.history(subjectUserId, subject, limit = 1)
            .firstOrNull()
            ?.observedAt
        return DeviceStateSnapshot(workerId, subjectUserId, values, lastEventAt)
    }

    suspend fun getUserState(userId: User.Id): UserStateSnapshot {
        val current = repository.currentState(userId)
        val history = repository.history(userId, limit = STATE_REDUCTION_HISTORY_LIMIT)
        val userSubject = ContextEvent.Subject.UserState(userId)
        val userEntries = current.filter { it.subject == userSubject }
        val devices = current
            .groupBy { (it.subject as? ContextEvent.Subject.Device)?.workerId }
            .filterKeys { it != null }
            .map { (workerId, entries) ->
                DeviceStateSnapshot(
                    workerId = requireNotNull(workerId),
                    subjectUserId = userId,
                    values = entries,
                    lastEventAt = history
                        .asSequence()
                        .filter { it.subject == ContextEvent.Subject.Device(workerId) }
                        .maxOfOrNull(ContextEvent::observedAt),
                )
            }
            .sortedBy { it.workerId.value }
        return UserStateSnapshot(
            userId = userId,
            activeClient = userEntries.firstOrNull {
                it.stateKey == ACTIVE_CLIENT_STATE_KEY &&
                    (it.payload as? ContextEvent.Payload.ActiveClient)?.active == true
            },
            declarations = current.filter { it.stateKey.startsWith(DECLARATION_STATE_KEY_PREFIX) },
            devices = devices,
            conflicts = declarationConflicts(current) + movementConflicts(history),
        )
    }

    suspend fun history(
        userId: User.Id,
        workerId: ConversationRuntimeWorkerId? = null,
        from: Instant? = null,
        to: Instant? = null,
        limit: Int = 100,
    ): List<ContextEvent> =
        repository.history(
            userId = userId,
            subject = workerId?.let(ContextEvent.Subject::Device),
            from = from,
            to = to,
            limit = limit,
        )

    private fun declarationConflicts(current: List<ContextStateEntry>): List<ContextStateConflict> =
        current.mapNotNull { declaration ->
            val declared = declaration.payload as? ContextEvent.Payload.UserDeclaration ?: return@mapNotNull null
            val actual = current.firstOrNull {
                it.subject == declaration.subject && it.stateKey == declared.stateKey
            } ?: return@mapNotNull null
            if (declared.value == actual.payload.normalizedValue()) return@mapNotNull null
            ContextStateConflict(
                kind = ContextStateConflict.Kind.DECLARATION_MISMATCH,
                subject = declaration.subject,
                stateKey = declared.stateKey,
                eventIds = listOf(declaration.eventId, actual.eventId),
                description = "User-declared value differs from the latest observed value",
            )
        }

    private fun movementConflicts(history: List<ContextEvent>): List<ContextStateConflict> =
        history
            .filter { (it.payload as? ContextEvent.Payload.Device)?.event is DeviceStateEvent.Location }
            .groupBy(ContextEvent::subject)
            .flatMap { (subject, events) ->
                events.sortedBy(ContextEvent::observedAt)
                    .zipWithNext()
                    .mapNotNull { (from, to) -> movementConflict(subject, from, to) }
            }
            .take(MAX_REPORTED_MOVEMENT_CONFLICTS)

    private fun movementConflict(
        subject: ContextEvent.Subject,
        from: ContextEvent,
        to: ContextEvent,
    ): ContextStateConflict? {
        val elapsedSeconds = (to.observedAt.toEpochMilliseconds() - from.observedAt.toEpochMilliseconds()) / 1_000.0
        if (elapsedSeconds <= 0) return null
        val fromLocation = ((from.payload as ContextEvent.Payload.Device).event as DeviceStateEvent.Location)
        val toLocation = ((to.payload as ContextEvent.Payload.Device).event as DeviceStateEvent.Location)
        val uncertaintyMeters = (fromLocation.accuracyMeters ?: 0.0) + (toLocation.accuracyMeters ?: 0.0)
        val distanceMeters = (haversineMeters(fromLocation, toLocation) - uncertaintyMeters).coerceAtLeast(0.0)
        val speedMetersPerSecond = distanceMeters / elapsedSeconds
        if (speedMetersPerSecond <= IMPLAUSIBLE_MOVEMENT_METERS_PER_SECOND) return null
        return ContextStateConflict(
            kind = ContextStateConflict.Kind.IMPLAUSIBLE_MOVEMENT,
            subject = subject,
            stateKey = "location",
            eventIds = listOf(from.id, to.id),
            description = "Observed movement implies ${speedMetersPerSecond.toInt()} m/s; both observations were preserved",
        )
    }

    private fun haversineMeters(
        from: DeviceStateEvent.Location,
        to: DeviceStateEvent.Location,
    ): Double {
        val fromLatitude = Math.toRadians(from.latitude)
        val toLatitude = Math.toRadians(to.latitude)
        val latitudeDelta = toLatitude - fromLatitude
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(fromLatitude) * cos(toLatitude) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return EARTH_RADIUS_METERS * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun ContextEvent.Payload.normalizedValue(): JsonElement =
        when (this) {
            is ContextEvent.Payload.Device -> contextStateJson.encodeToJsonElement(DeviceStateEvent.serializer(), event)
            is ContextEvent.Payload.ActiveClient -> contextStateJson.encodeToJsonElement(
                ContextEvent.Payload.ActiveClient.serializer(),
                this,
            )
            is ContextEvent.Payload.UserDeclaration -> value
        }

    private fun mobileEventId(workerId: ConversationRuntimeWorkerId, localId: String): ContextEventId =
        ContextEventId("mobile:${workerId.value}:$localId")

    private companion object {
        const val ACTIVE_CLIENT_STATE_KEY = "active_client"
        const val DECLARATION_STATE_KEY_PREFIX = "declaration:"
        const val MAX_DEVICE_OBSERVATION_BATCH_SIZE = 100
        const val STATE_REDUCTION_HISTORY_LIMIT = 1_000
        const val MAX_REPORTED_MOVEMENT_CONFLICTS = 50
        const val IMPLAUSIBLE_MOVEMENT_METERS_PER_SECOND = 300.0
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}

data class DeviceObservationAppendResult(
    val acceptedIds: Set<String>,
    val duplicateIds: Set<String>,
    val receivedAt: Instant,
)

private val contextStateJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}
