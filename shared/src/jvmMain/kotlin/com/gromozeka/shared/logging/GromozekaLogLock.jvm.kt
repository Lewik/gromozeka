package com.gromozeka.shared.logging

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal actual class GromozekaLogLock actual constructor() {
    private val lock = ReentrantLock()

    actual fun <T> withLock(block: () -> T): T = lock.withLock(block)
}
