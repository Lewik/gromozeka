package com.gromozeka.presentation.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/** One gesture vocabulary for pointer and keyboard: click, double click, hold. */
class UnifiedGestureDetector(
    private val handler: PttEventHandler,
    private val scope: CoroutineScope,
    private val currentTimeMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val holdThreshold = 150.milliseconds
    private val doubleClickWindow = 400.milliseconds
    private var pressedAt: Long? = null
    private var lastClickAt: Long? = null
    private var holdTimer: Job? = null
    private var captureStart: Job? = null

    fun onGestureDown() {
        if (pressedAt != null) return // Key-repeat is not another press.
        pressedAt = currentTimeMillis()
        holdTimer = scope.launch {
            delay(holdThreshold)
            startHold()
        }
    }

    fun onGestureUp() {
        val startedAt = pressedAt ?: return
        val now = currentTimeMillis()
        pressedAt = null
        holdTimer?.cancel()
        holdTimer = null
        if (captureStart != null || now - startedAt >= holdThreshold.inWholeMilliseconds) {
            startHold()
            finishHold(cancel = false)
            lastClickAt = null
            return
        }

        val doubleClick = lastClickAt?.let { now - it <= doubleClickWindow.inWholeMilliseconds } == true
        lastClickAt = if (doubleClick) null else now
        scope.launch {
            handler.handlePTTEvent(if (doubleClick) PTTEvent.DOUBLE_CLICK else PTTEvent.SINGLE_CLICK)
        }
    }

    fun cancelGesture() {
        pressedAt = null
        lastClickAt = null
        holdTimer?.cancel()
        holdTimer = null
        finishHold(cancel = true)
    }

    private fun startHold() {
        if (captureStart != null || !handler.canRecord) return
        captureStart = scope.launch { handler.handlePTTEvent(PTTEvent.SINGLE_PUSH) }
    }

    private fun finishHold(cancel: Boolean) {
        val start = captureStart ?: return
        captureStart = null
        scope.launch {
            start.join()
            if (cancel) handler.handlePTTCancel() else handler.handlePTTRelease()
        }
    }
}
