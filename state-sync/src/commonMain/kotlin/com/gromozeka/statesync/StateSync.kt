package com.gromozeka.statesync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class StateSyncCursor(
    val sourceEpoch: String,
    val streamEpoch: Long,
    val generation: Long,
) {
    init {
        require(sourceEpoch.isNotBlank()) { "State sync source epoch must not be blank" }
        require(streamEpoch >= 0) { "State sync stream epoch must not be negative" }
        require(generation >= 0) { "State sync generation must not be negative" }
    }
}

data class StateSyncInvalidation<K : Any>(
    val key: K,
    val cursor: StateSyncCursor,
)

data class StateSyncSnapshot<K : Any, out V>(
    val key: K,
    val cursor: StateSyncCursor,
    val value: V,
)

interface StateSyncSubscription<K : Any, V> {
    val key: K
    val invalidations: Flow<StateSyncInvalidation<K>>

    suspend fun snapshot(): StateSyncSnapshot<K, V>
    suspend fun close()
}

interface StateSyncService<K : Any, V> {
    suspend fun subscribe(key: K): StateSyncSubscription<K, V>
    suspend fun snapshot(key: K): StateSyncSnapshot<K, V>
    suspend fun invalidate(key: K)
}

class StateSyncSource<K : Any, V>(
    private val scope: CoroutineScope,
    sourceEpoch: String,
    private val loader: suspend (K) -> V,
) : StateSyncService<K, V> {
    private val sourceEpoch = sourceEpoch.also {
        require(it.isNotBlank()) { "State sync source epoch must not be blank" }
    }
    private val mutex = Mutex()
    private val entries = mutableMapOf<K, Entry<K, V>>()
    private var nextStreamEpoch = 0L

    override suspend fun subscribe(key: K): StateSyncSubscription<K, V> {
        val entry = mutex.withLock {
            entry(key).also { it.subscriberCount += 1 }
        }
        return SourceSubscription(entry)
    }

    override suspend fun snapshot(key: K): StateSyncSnapshot<K, V> {
        val entry = mutex.withLock { entry(key) }
        return snapshot(entry)
    }

    override suspend fun invalidate(key: K) {
        mutex.withLock {
            val entry = entries[key] ?: return@withLock
            entry.generation += 1
            entry.invalidations.value = entry.invalidation()
        }
    }

    private fun entry(key: K): Entry<K, V> =
        entries.getOrPut(key) {
            val streamEpoch = nextStreamEpoch++
            val cursor = StateSyncCursor(sourceEpoch, streamEpoch, 0)
            Entry(
                key = key,
                sourceEpoch = sourceEpoch,
                streamEpoch = streamEpoch,
                invalidations = MutableStateFlow(StateSyncInvalidation(key, cursor)),
            )
        }

    private suspend fun snapshot(entry: Entry<K, V>): StateSyncSnapshot<K, V> {
        while (true) {
            var cached: StateSyncSnapshot<K, V>? = null
            val inFlight = mutex.withLock {
                if (entry.loadedGeneration == entry.generation) {
                    cached = entry.cached
                    null
                } else {
                    entry.inFlight ?: InFlight(
                        generation = entry.generation,
                        deferred = scope.async { loader(entry.key) },
                    ).also { entry.inFlight = it }
                }
            }
            cached?.let { return it }
            checkNotNull(inFlight) { "State sync entry is fresh without a cached snapshot" }

            val value = try {
                inFlight.deferred.await()
            } catch (error: Throwable) {
                if (error is CancellationException && !inFlight.deferred.isCancelled) {
                    settleAbandonedLoad(entry, inFlight)
                } else {
                    mutex.withLock {
                        if (entry.inFlight === inFlight) {
                            entry.inFlight = null
                            removeUnused(entry)
                        }
                    }
                }
                throw error
            }

            val result = mutex.withLock {
                if (entry.inFlight === inFlight) {
                    entry.cached = StateSyncSnapshot(
                        key = entry.key,
                        cursor = StateSyncCursor(
                            sourceEpoch = entry.sourceEpoch,
                            streamEpoch = entry.streamEpoch,
                            generation = inFlight.generation,
                        ),
                        value = value,
                    )
                    entry.loadedGeneration = inFlight.generation
                    entry.inFlight = null
                }
                val snapshot = checkNotNull(entry.cached)
                val stable = entry.loadedGeneration == entry.generation
                if (stable) {
                    removeUnused(entry)
                }
                snapshot to stable
            }
            if (result.second) {
                return result.first
            }
        }
    }

    private fun settleAbandonedLoad(entry: Entry<K, V>, inFlight: InFlight<V>) {
        scope.launch {
            val value = try {
                inFlight.deferred.await()
            } catch (_: Throwable) {
                mutex.withLock {
                    if (entry.inFlight === inFlight) {
                        entry.inFlight = null
                        removeUnused(entry)
                    }
                }
                return@launch
            }
            mutex.withLock {
                if (entry.inFlight === inFlight) {
                    entry.cached = StateSyncSnapshot(
                        key = entry.key,
                        cursor = StateSyncCursor(
                            sourceEpoch = entry.sourceEpoch,
                            streamEpoch = entry.streamEpoch,
                            generation = inFlight.generation,
                        ),
                        value = value,
                    )
                    entry.loadedGeneration = inFlight.generation
                    entry.inFlight = null
                    if (entry.subscriberCount == 0) {
                        removeUnused(entry)
                    }
                }
            }
        }
    }

    private fun removeUnused(entry: Entry<K, V>) {
        if (entry.subscriberCount == 0 && entry.inFlight == null && entries[entry.key] === entry) {
            entries.remove(entry.key)
        }
    }

    private suspend fun close(entry: Entry<K, V>) {
        mutex.withLock {
            check(entry.subscriberCount > 0) { "State sync subscription was already closed" }
            entry.subscriberCount -= 1
            removeUnused(entry)
        }
    }

    private inner class SourceSubscription(
        private val entry: Entry<K, V>,
    ) : StateSyncSubscription<K, V> {
        private val closeMutex = Mutex()
        private var closed = false

        override val key: K = entry.key
        override val invalidations: Flow<StateSyncInvalidation<K>> = entry.invalidations

        override suspend fun snapshot(): StateSyncSnapshot<K, V> {
            check(!closed) { "State sync subscription is closed" }
            return this@StateSyncSource.snapshot(entry)
        }

        override suspend fun close() {
            closeMutex.withLock {
                if (closed) return
                closed = true
                this@StateSyncSource.close(entry)
            }
        }
    }

    private data class Entry<K : Any, V>(
        val key: K,
        val sourceEpoch: String,
        val streamEpoch: Long,
        val invalidations: MutableStateFlow<StateSyncInvalidation<K>>,
        var generation: Long = 0,
        var loadedGeneration: Long = -1,
        var cached: StateSyncSnapshot<K, V>? = null,
        var inFlight: InFlight<V>? = null,
        var subscriberCount: Int = 0,
    ) {
        fun invalidation(): StateSyncInvalidation<K> = StateSyncInvalidation(
            key = key,
            cursor = StateSyncCursor(sourceEpoch, streamEpoch, generation),
        )
    }

    private data class InFlight<V>(
        val generation: Long,
        val deferred: Deferred<V>,
    )
}

class StateSyncReplica<K : Any, V>(
    private val key: K,
) {
    private var current: StateSyncSnapshot<K, V>? = null
    private val retiredSourceEpochs = mutableSetOf<String>()

    val snapshot: StateSyncSnapshot<K, V>?
        get() = current

    fun apply(snapshot: StateSyncSnapshot<K, V>): Boolean {
        require(snapshot.key == key) {
            "State sync snapshot key ${snapshot.key} does not match replica key $key"
        }
        val previous = current
        if (previous != null && !accepts(snapshot.cursor, previous.cursor)) {
            return false
        }
        current = snapshot
        return true
    }

    private fun accepts(candidate: StateSyncCursor, previous: StateSyncCursor): Boolean {
        if (candidate.sourceEpoch != previous.sourceEpoch) {
            if (candidate.sourceEpoch in retiredSourceEpochs) {
                return false
            }
            retiredSourceEpochs += previous.sourceEpoch
            return true
        }
        return candidate.isNewerWithinSourceThan(previous)
    }
}

fun <K : Any, V> observeStateSync(
    key: K,
    invalidations: Flow<StateSyncInvalidation<K>>,
    pull: suspend (StateSyncInvalidation<K>) -> StateSyncSnapshot<K, V>,
): Flow<StateSyncSnapshot<K, V>> = flow {
    val replica = StateSyncReplica<K, V>(key)
    invalidations.conflate().collect { invalidation ->
        require(invalidation.key == key) {
            "State sync invalidation key ${invalidation.key} does not match observed key $key"
        }
        val snapshot = pull(invalidation)
        require(snapshot.cursor.isAtLeast(invalidation.cursor)) {
            "State sync snapshot cursor ${snapshot.cursor} is older than invalidation ${invalidation.cursor}"
        }
        if (replica.apply(snapshot)) {
            emit(snapshot)
        }
    }
}

fun <K : Any, V> StateSyncService<K, V>.observe(key: K): Flow<StateSyncSnapshot<K, V>> = flow {
    val subscription = subscribe(key)
    try {
        observeStateSync(
            key = key,
            invalidations = subscription.invalidations,
            pull = { subscription.snapshot() },
        ).collect { emit(it) }
    } finally {
        subscription.close()
    }
}

private fun StateSyncCursor.isNewerWithinSourceThan(previous: StateSyncCursor): Boolean =
    if (streamEpoch != previous.streamEpoch) {
        streamEpoch > previous.streamEpoch
    } else {
        generation > previous.generation
    }

private fun StateSyncCursor.isAtLeast(invalidation: StateSyncCursor): Boolean =
    sourceEpoch != invalidation.sourceEpoch ||
        streamEpoch > invalidation.streamEpoch ||
        (streamEpoch == invalidation.streamEpoch && generation >= invalidation.generation)
