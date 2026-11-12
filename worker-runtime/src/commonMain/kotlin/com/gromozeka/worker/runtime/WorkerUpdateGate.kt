package com.gromozeka.worker.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WorkerUpdateGate {
    private val mutex = Mutex()
    private var active = 0
    private var updating = false

    suspend fun enter(): Boolean = mutex.withLock { if (updating) false else { active++; true } }
    suspend fun leave() = mutex.withLock { check(active > 0); active-- }

    suspend fun prepare(): Boolean = mutex.withLock { if (active != 0) false else { updating = true; true } }
    suspend fun resume() = mutex.withLock { updating = false }
}
