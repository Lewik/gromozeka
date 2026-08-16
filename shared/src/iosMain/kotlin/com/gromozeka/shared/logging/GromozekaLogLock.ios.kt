package com.gromozeka.shared.logging

import platform.Foundation.NSLock

internal actual class GromozekaLogLock actual constructor() {
    private val lock = NSLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}
