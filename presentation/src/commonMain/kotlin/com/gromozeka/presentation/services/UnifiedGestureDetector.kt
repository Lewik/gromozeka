package com.gromozeka.presentation.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.milliseconds

class UnifiedGestureDetector(
    private val pttEventRouter: PttEventHandler,
    private val coroutineScope: CoroutineScope,
) {
    private val doubleClickWindow = 400.milliseconds
    private val shortClickThreshold = 150.milliseconds
    private var state = GestureState.IDLE
    private var firstPressTime = 0L
    private var currentPressTime = 0L
    private var timeoutJob: Job? = null

    fun onGestureDown() {
        val now = Clock.System.now().toEpochMilliseconds()
        currentPressTime = now

        when (state) {
            GestureState.IDLE -> {
                firstPressTime = now
                state = GestureState.FIRST_DOWN

                // Start PTT immediately on first button press
                dispatch { handlePTTEvent(PTTEvent.BUTTON_DOWN) }

                // If holding long - this is single hold
                timeoutJob = coroutineScope.launch {
                    delay(shortClickThreshold)
                    if (state == GestureState.FIRST_DOWN) {
                        state = GestureState.SINGLE_HOLDING
                        dispatch { handlePTTEvent(PTTEvent.SINGLE_PUSH) }
                    }
                }
            }

            GestureState.WAITING_SECOND_DOWN -> {
                if (now - firstPressTime < doubleClickWindow.inWholeMilliseconds) {
                    // Second press within window
                    timeoutJob?.cancel()
                    state = GestureState.SECOND_DOWN

                    // Start PTT immediately on second press too; short double-click will cancel it on release
                    dispatch { handlePTTEvent(PTTEvent.BUTTON_DOWN) }

                    // If holding - this is double hold
                    timeoutJob = coroutineScope.launch {
                        delay(shortClickThreshold)
                        if (state == GestureState.SECOND_DOWN) {
                            state = GestureState.DOUBLE_HOLDING
                            dispatch { handlePTTEvent(PTTEvent.DOUBLE_PUSH) }
                        }
                    }
                } else {
                    // Too late, start over
                    firstPressTime = now
                    state = GestureState.FIRST_DOWN

                    // Start PTT immediately on new gesture
                    dispatch { handlePTTEvent(PTTEvent.BUTTON_DOWN) }

                    timeoutJob = coroutineScope.launch {
                        delay(shortClickThreshold)
                        if (state == GestureState.FIRST_DOWN) {
                            state = GestureState.SINGLE_HOLDING
                            dispatch { handlePTTEvent(PTTEvent.SINGLE_PUSH) }
                        }
                    }
                }
            }

            else -> {
                // In states FIRST_DOWN, SECOND_DOWN, SINGLE_HOLDING, DOUBLE_HOLDING
                // ignore additional presses
            }
        }
    }

    fun onGestureUp() {
        val now = Clock.System.now().toEpochMilliseconds()
        val holdDuration = now - currentPressTime

        when (state) {
            GestureState.FIRST_DOWN -> {
                timeoutJob?.cancel()

                if (holdDuration < shortClickThreshold.inWholeMilliseconds) {
                    dispatch { handlePTTCancel() }
                    // Quick press, waiting for second
                    state = GestureState.WAITING_SECOND_DOWN

                    timeoutJob = coroutineScope.launch {
                        delay(doubleClickWindow)
                        if (state == GestureState.WAITING_SECOND_DOWN) {
                            state = GestureState.IDLE
                            dispatch { handlePTTEvent(PTTEvent.SINGLE_CLICK) }
                        }
                    }
                } else {
                    // Preserve hold semantics when a busy event loop delays the threshold timer.
                    state = GestureState.IDLE
                    dispatchSequentially(
                        { handlePTTEvent(PTTEvent.SINGLE_PUSH) },
                        { handlePTTRelease() },
                    )
                }
            }

            GestureState.SECOND_DOWN -> {
                timeoutJob?.cancel()

                if (holdDuration < shortClickThreshold.inWholeMilliseconds) {
                    dispatch { handlePTTCancel() }
                    // Quick double click
                    state = GestureState.IDLE
                    dispatch { handlePTTEvent(PTTEvent.DOUBLE_CLICK) }
                } else {
                    // Preserve hold semantics when a busy event loop delays the threshold timer.
                    state = GestureState.IDLE
                    dispatchSequentially(
                        { handlePTTEvent(PTTEvent.DOUBLE_PUSH) },
                        { handlePTTRelease() },
                    )
                }
            }

            GestureState.SINGLE_HOLDING -> {
                state = GestureState.IDLE
                dispatch { handlePTTRelease() }
            }

            GestureState.DOUBLE_HOLDING -> {
                state = GestureState.IDLE
                dispatch { handlePTTRelease() }
            }

            else -> {
                // In other states ignore UP events
            }
        }
    }

    fun resetGestureState() {
        timeoutJob?.cancel()
        state = GestureState.IDLE
    }

    fun cancelGesture() {
        val hasActivePress = state == GestureState.FIRST_DOWN ||
            state == GestureState.SECOND_DOWN ||
            state == GestureState.SINGLE_HOLDING ||
            state == GestureState.DOUBLE_HOLDING
        resetGestureState()
        if (hasActivePress) {
            dispatch { handlePTTCancel() }
        }
    }

    private fun dispatch(action: suspend PttEventHandler.() -> Unit) {
        coroutineScope.launch {
            pttEventRouter.action()
        }
    }

    private fun dispatchSequentially(vararg actions: suspend PttEventHandler.() -> Unit) {
        coroutineScope.launch {
            actions.forEach { action -> pttEventRouter.action() }
        }
    }
}

enum class GestureState {
    IDLE,
    FIRST_DOWN,
    WAITING_SECOND_DOWN,
    SECOND_DOWN,
    SINGLE_HOLDING,
    DOUBLE_HOLDING
}
