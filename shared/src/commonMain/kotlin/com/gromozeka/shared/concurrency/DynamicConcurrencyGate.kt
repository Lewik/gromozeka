package com.gromozeka.shared.concurrency

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DynamicConcurrencyGate {
    private val mutex = Mutex()
    private val changed = MutableStateFlow(0L)
    private var active = 0

    suspend fun <T> withPermit(limit: Int, block: suspend () -> T): T {
        require(limit > 0) { "Concurrency limit must be positive" }
        acquire(limit)
        return try {
            block()
        } finally {
            withContext(NonCancellable) { release() }
        }
    }

    private suspend fun acquire(limit: Int) {
        while (true) {
            val observed = changed.value
            val acquired = mutex.withLock {
                if (active < limit) {
                    active++
                    changed.value++
                    true
                } else {
                    false
                }
            }
            if (acquired) return
            changed.first { it != observed }
        }
    }

    private suspend fun release() {
        mutex.withLock {
            check(active > 0) { "Concurrency permit underflow" }
            active--
            changed.value++
        }
    }
}
