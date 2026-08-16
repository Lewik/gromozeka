package com.gromozeka.shared.logging

internal actual class GromozekaLogLock actual constructor() {
    actual fun <T> withLock(block: () -> T): T = block()
}
