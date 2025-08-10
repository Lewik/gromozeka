package com.gromozeka.bot.services

import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

class UnifiedGestureDetector(
    private val pttEventRouter: PTTEventRouter,
    private val coroutineScope: CoroutineScope
) {
    private val doubleClickWindow = 400.milliseconds
    private val shortClickThreshold = 150.milliseconds
    private var state = GestureState.IDLE
    private var firstPressTime = 0L
    private var currentPressTime = 0L
    private var timeoutJob: Job? = null

    suspend fun onGestureDown() {
        val now = System.currentTimeMillis()
        currentPressTime = now
        
        when (state) {
            GestureState.IDLE -> {
                firstPressTime = now
                state = GestureState.FIRST_DOWN
                
                // If holding long - this is single hold
                timeoutJob = coroutineScope.launch {
                    delay(shortClickThreshold)
                    if (state == GestureState.FIRST_DOWN) {
                        state = GestureState.SINGLE_HOLDING
                        pttEventRouter.handlePTTEvent(PTTEvent.SINGLE_PUSH)
                    }
                }
            }
            
            GestureState.WAITING_SECOND_DOWN -> {
                if (now - firstPressTime < doubleClickWindow.inWholeMilliseconds) {
                    // Second press within window
                    timeoutJob?.cancel()
                    state = GestureState.SECOND_DOWN
                    
                    // If holding - this is double hold
                    timeoutJob = coroutineScope.launch {
                        delay(shortClickThreshold)
                        if (state == GestureState.SECOND_DOWN) {
                            state = GestureState.DOUBLE_HOLDING
                            pttEventRouter.handlePTTEvent(PTTEvent.DOUBLE_PUSH)
                        }
                    }
                } else {
                    // Too late, start over
                    firstPressTime = now
                    state = GestureState.FIRST_DOWN
                    
                    timeoutJob = coroutineScope.launch {
                        delay(shortClickThreshold)
                        if (state == GestureState.FIRST_DOWN) {
                            state = GestureState.SINGLE_HOLDING
                            pttEventRouter.handlePTTEvent(PTTEvent.SINGLE_PUSH)
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

    suspend fun onGestureUp() {
        val now = System.currentTimeMillis()
        val holdDuration = now - currentPressTime
        
        when (state) {
            GestureState.FIRST_DOWN -> {
                timeoutJob?.cancel()
                
                if (holdDuration < shortClickThreshold.inWholeMilliseconds) {
                    // Quick press, waiting for second
                    state = GestureState.WAITING_SECOND_DOWN
                    
                    timeoutJob = coroutineScope.launch {
                        delay(doubleClickWindow)
                        if (state == GestureState.WAITING_SECOND_DOWN) {
                            state = GestureState.IDLE
                            pttEventRouter.handlePTTEvent(PTTEvent.SINGLE_CLICK)
                        }
                    }
                } else {
                    // This was single hold, but released early
                    state = GestureState.IDLE
                }
            }
            
            GestureState.SECOND_DOWN -> {
                timeoutJob?.cancel()
                
                if (holdDuration < shortClickThreshold.inWholeMilliseconds) {
                    // Quick double click
                    state = GestureState.IDLE
                    pttEventRouter.handlePTTEvent(PTTEvent.DOUBLE_CLICK)
                } else {
                    // This was double hold, but released early
                    state = GestureState.IDLE
                }
            }
            
            GestureState.SINGLE_HOLDING -> {
                state = GestureState.IDLE
                pttEventRouter.handlePTTRelease()
            }
            
            GestureState.DOUBLE_HOLDING -> {
                state = GestureState.IDLE
                pttEventRouter.handlePTTRelease()
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
    
    fun getCurrentState(): GestureState = state
    
    fun isInHoldingState(): Boolean = 
        state == GestureState.SINGLE_HOLDING || state == GestureState.DOUBLE_HOLDING
}

enum class GestureState {
    IDLE,
    FIRST_DOWN,
    WAITING_SECOND_DOWN,
    SECOND_DOWN,
    SINGLE_HOLDING,
    DOUBLE_HOLDING
}