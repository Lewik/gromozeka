package com.gromozeka.mobile.worker

import com.gromozeka.domain.model.DeviceCollectionGapReason
import com.gromozeka.domain.model.DeviceCollectionSource
import com.gromozeka.domain.model.DeviceCollectionState
import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.remote.protocol.WorkerEventInput
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Serializable
data class WorkerTelemetryConfiguration(
    val enabled: Boolean = false,
    val applicationUsageEnabled: Boolean = false,
    val intervalSeconds: Int = 30,
) {
    init { require(intervalSeconds in 10..900) { "Collection interval must be between 10 and 900 seconds" } }
}

@Serializable
internal data class WorkerTelemetryCheckpoint(
    val usageFloor: Instant,
    val usageThrough: Instant,
    val usageEpoch: String,
    val usageAvailable: Boolean? = null,
    val recentUsageIds: List<String> = emptyList(),
    val lastCollectedAt: Instant? = null,
) {
    init {
        require(usageThrough >= usageFloor)
        require(recentUsageIds.size <= MAX_USAGE_EVENTS_PER_QUERY)
    }
}

internal data class MobileWorkerTelemetryCollection(
    val streamId: String,
    val revision: String,
    val configuration: WorkerTelemetryConfiguration,
    val checkpoint: WorkerTelemetryCheckpoint,
)

internal interface MobileWorkerTelemetrySource {
    fun deviceState(): List<DeviceStateEvent>
    fun usageAccess(): DeviceCollectionState
    fun usageEvents(from: Instant, to: Instant, epoch: String): List<WorkerEventInput>
    fun applicationInfo(packageName: String): DeviceStateEvent.ApplicationInfo
}

/** No transport, LLM, usage interpretation or platform scheduling in this collector. */
internal class MobileWorkerTelemetryCollector(
    private val runtime: MobileWorkerRuntime,
    private val source: MobileWorkerTelemetrySource,
    private val now: () -> Instant = { Clock.System.now() },
) {
    /** True when another bounded history page is ready. Collection never requires a network. */
    suspend fun collect(): Boolean {
        val collection = runtime.telemetryCollection() ?: return false
        val time = now()
        val events = mutableListOf<WorkerEventInput>()
        fun record(payload: DeviceStateEvent) { events += WorkerEventInput(uuid7(), time, payload) }
        try {
            source.deviceState().forEach(::record)
            record(DeviceStateEvent.CollectionStatus(DeviceCollectionSource.DEVICE, DeviceCollectionState.ACTIVE))
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            record(DeviceStateEvent.CollectionStatus(DeviceCollectionSource.DEVICE, DeviceCollectionState.ERROR))
        }
        val result = try { collectUsage(collection, time) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            UsageResult(listOf(WorkerEventInput(uuid7(), time,
                DeviceStateEvent.CollectionStatus(DeviceCollectionSource.APP_USAGE, DeviceCollectionState.ERROR))), collection.checkpoint)
        }
        events += result.events
        runtime.recordTelemetry(collection, events, result.checkpoint.copy(lastCollectedAt = time))
        return result.events.any { (it.payload as? DeviceStateEvent.CollectionStatus)?.let { status ->
            status.source == DeviceCollectionSource.APP_USAGE && status.state == DeviceCollectionState.ACTIVE
        } == true } && result.checkpoint.usageThrough < time - USAGE_SETTLE_DELAY
    }

    private fun collectUsage(collection: MobileWorkerTelemetryCollection, time: Instant): UsageResult {
        val previous = collection.checkpoint
        val access = if (collection.configuration.applicationUsageEnabled) source.usageAccess() else DeviceCollectionState.DISABLED
        fun event(payload: DeviceStateEvent) = WorkerEventInput(uuid7(), time, payload)
        if (access != DeviceCollectionState.ACTIVE) {
            val events = mutableListOf<WorkerEventInput>()
            if (access != DeviceCollectionState.DISABLED && time > previous.usageThrough) {
                events += event(DeviceStateEvent.CollectionGap(DeviceCollectionSource.APP_USAGE, previous.usageThrough, time,
                    DeviceCollectionGapReason.ACCESS_UNAVAILABLE))
            }
            events += event(DeviceStateEvent.CollectionStatus(DeviceCollectionSource.APP_USAGE, access))
            return UsageResult(events, previous.copy(usageFloor = time, usageThrough = time,
                usageAvailable = false, recentUsageIds = emptyList()))
        }
        var checkpoint = previous
        val events = mutableListOf<WorkerEventInput>()
        if (time < checkpoint.usageThrough || checkpoint.usageAvailable == false) {
            // Never import the period when consent/access was absent. A clock rollback starts a new identity epoch.
            val reason = if (time < checkpoint.usageThrough) DeviceCollectionGapReason.CLOCK_CHANGED else DeviceCollectionGapReason.ACCESS_UNAVAILABLE
            events += event(DeviceStateEvent.CollectionGap(DeviceCollectionSource.APP_USAGE,
                minOf(time, checkpoint.usageThrough), maxOf(time, checkpoint.usageThrough), reason))
            checkpoint = checkpoint.copy(usageFloor = time, usageThrough = time, usageEpoch = uuid7(), recentUsageIds = emptyList())
        }
        val oldest = time - MAX_USAGE_BACKFILL
        if (checkpoint.usageThrough < oldest) {
            events += event(DeviceStateEvent.CollectionGap(DeviceCollectionSource.APP_USAGE, checkpoint.usageThrough, oldest,
                DeviceCollectionGapReason.HISTORY_LIMIT))
            checkpoint = checkpoint.copy(usageFloor = oldest, usageThrough = oldest, recentUsageIds = emptyList())
        }
        val from = maxOf(checkpoint.usageFloor, checkpoint.usageThrough - USAGE_OVERLAP)
        val through = minOf(time - USAGE_SETTLE_DELAY, checkpoint.usageThrough + MAX_USAGE_QUERY_WINDOW)
        if (through <= from) {
            events += event(DeviceStateEvent.CollectionStatus(DeviceCollectionSource.APP_USAGE, DeviceCollectionState.STARTING))
            return UsageResult(events, checkpoint.copy(usageAvailable = true))
        }
        return try {
            val queried = source.usageEvents(from, through, checkpoint.usageEpoch)
            require(queried.size <= MAX_USAGE_EVENTS_PER_QUERY) { "Usage query exceeded its bounded result size" }
            require(queried.all { it.observedAt >= from && it.observedAt < through })
            require(queried.map { it.id }.distinct().size == queried.size)
            val alreadySeen = checkpoint.recentUsageIds.toSet()
            val fresh = queried.filter { it.id !in alreadySeen }
            val packages = fresh.mapNotNull { (it.payload as? DeviceStateEvent.AppActivity)?.packageName }.distinct()
            for (packageName in packages) {
                // Package visibility restrictions must not discard otherwise valid usage history.
                val metadata = try { source.applicationInfo(packageName) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { DeviceStateEvent.ApplicationInfo(packageName) }
                events += event(metadata)
            }
            events += fresh
            events += event(DeviceStateEvent.CollectionStatus(DeviceCollectionSource.APP_USAGE,
                DeviceCollectionState.ACTIVE, from, through))
            UsageResult(events, checkpoint.copy(usageThrough = through, usageAvailable = true,
                recentUsageIds = queried.filter { it.observedAt >= through - USAGE_OVERLAP }.map { it.id }))
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            // Preserve the old cursor on a failed query. Do not claim coverage or commit a speculative gap.
            UsageResult(listOf(event(DeviceStateEvent.CollectionStatus(DeviceCollectionSource.APP_USAGE,
                DeviceCollectionState.ERROR))), previous)
        }
    }

    private data class UsageResult(val events: List<WorkerEventInput>, val checkpoint: WorkerTelemetryCheckpoint)
}

internal const val MAX_USAGE_EVENTS_PER_QUERY = 2_000
private val MAX_USAGE_BACKFILL = 24.hours
private val MAX_USAGE_QUERY_WINDOW = 5.minutes
private val USAGE_OVERLAP = 10.seconds
private val USAGE_SETTLE_DELAY = 2.seconds
