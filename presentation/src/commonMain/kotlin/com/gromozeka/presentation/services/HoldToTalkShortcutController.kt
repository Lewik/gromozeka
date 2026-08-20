package com.gromozeka.presentation.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

class HoldToTalkShortcutController(
    private val pttEventHandler: PttEventHandler,
    private val coroutineScope: CoroutineScope,
    private val currentTimeMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val holdThreshold = 150.milliseconds
    private var pressedAt: Long? = null
    private var holdJob: Job? = null
    private var holdConfirmed = false

    fun onPressed() {
        if (pressedAt != null) return
        pressedAt = currentTimeMillis()
        holdConfirmed = false
        coroutineScope.launch { pttEventHandler.handlePTTEvent(PTTEvent.BUTTON_DOWN) }
        holdJob = coroutineScope.launch {
            delay(holdThreshold)
            if (pressedAt != null) {
                holdConfirmed = true
                pttEventHandler.handlePTTEvent(PTTEvent.SINGLE_PUSH)
            }
        }
    }

    fun onReleased() {
        val startedAt = pressedAt ?: return
        pressedAt = null
        holdJob?.cancel()
        holdJob = null
        val heldLongEnough = currentTimeMillis() - startedAt >= holdThreshold.inWholeMilliseconds
        coroutineScope.launch {
            if (!heldLongEnough) {
                pttEventHandler.handlePTTCancel()
                return@launch
            }
            if (!holdConfirmed) {
                pttEventHandler.handlePTTEvent(PTTEvent.SINGLE_PUSH)
            }
            pttEventHandler.handlePTTRelease()
        }
    }

    fun cancel() {
        val wasPressed = pressedAt != null
        pressedAt = null
        holdJob?.cancel()
        holdJob = null
        holdConfirmed = false
        if (wasPressed) {
            coroutineScope.launch { pttEventHandler.handlePTTCancel() }
        }
    }
}
