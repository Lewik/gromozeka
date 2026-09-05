package com.gromozeka.worker.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class WorkerSoundVolumeOverride(
    private val readSnapshot: suspend () -> String?,
    private val writeSnapshot: suspend (String) -> Unit,
    private val currentVolume: () -> Int?,
    private val setVolume: (Int) -> Unit,
) {
    suspend fun boost(maximum: Int) {
        restore()
        val previous = requireNotNull(currentVolume()) { "Current alarm volume cannot be inspected safely" }
        require(maximum > 0 && previous in 0..maximum) { "Device alarm volume is unavailable" }
        persist(Snapshot(previous, maximum))
        setVolume(maximum)
        require(currentVolume() == maximum) { "Device did not allow maximum alarm volume" }
    }

    suspend fun restore() {
        val snapshot = readSnapshot()?.let { Json.decodeFromString<Snapshot>(it) } ?: return
        if (snapshot.previous == null) return
        val current = currentVolume() ?: return
        if (current == snapshot.applied) {
            setVolume(snapshot.previous)
            check(currentVolume() == snapshot.previous) { "Alarm volume could not be restored" }
        }
        persist(Snapshot())
    }

    private suspend fun persist(snapshot: Snapshot) {
        val encoded = Json.encodeToString(snapshot)
        writeSnapshot(encoded)
        check(readSnapshot() == encoded) { "Alarm volume recovery state could not be persisted" }
    }

    @Serializable
    private data class Snapshot(val previous: Int? = null, val applied: Int? = null) {
        init {
            require((previous == null && applied == null) || (previous != null && applied != null && previous >= 0 && previous <= applied)) {
                "Stored alarm volume override is invalid"
            }
        }
    }
}
