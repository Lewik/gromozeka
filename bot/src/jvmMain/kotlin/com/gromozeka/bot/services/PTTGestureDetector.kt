package com.gromozeka.bot.services

import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

class PTTGestureDetector(
    private val handler: PTTGestureHandler,
    private val coroutineScope: CoroutineScope
) {
    private val doubleClickWindow = 400.milliseconds
    private val shortClickThreshold = 150.milliseconds
    private var state = PTTState.IDLE
    private var firstPressTime = 0L
    private var currentPressTime = 0L
    private var timeoutJob: Job? = null

    suspend fun onKeyDown() {
        val now = System.currentTimeMillis()
        currentPressTime = now
        
        when (state) {
            PTTState.IDLE -> {
                firstPressTime = now
                state = PTTState.FIRST_DOWN
                
                // If holding long - this is single hold
                timeoutJob = coroutineScope.launch {
                    delay(shortClickThreshold)
                    if (state == PTTState.FIRST_DOWN) {
                        state = PTTState.SINGLE_HOLDING
                        handler.onPTTStart(PTTMode.SINGLE)
                    }
                }
            }
            
            PTTState.WAITING_SECOND_DOWN -> {
                if (now - firstPressTime < doubleClickWindow.inWholeMilliseconds) {
                    // Second press within window
                    timeoutJob?.cancel()
                    state = PTTState.SECOND_DOWN
                    
                    // If holding - this is double hold
                    timeoutJob = coroutineScope.launch {
                        delay(shortClickThreshold)
                        if (state == PTTState.SECOND_DOWN) {
                            state = PTTState.DOUBLE_HOLDING
                            handler.onPTTStart(PTTMode.DOUBLE)
                        }
                    }
                } else {
                    // Too late, start over
                    firstPressTime = now
                    state = PTTState.FIRST_DOWN
                    
                    timeoutJob = coroutineScope.launch {
                        delay(shortClickThreshold)
                        if (state == PTTState.FIRST_DOWN) {
                            state = PTTState.SINGLE_HOLDING
                            handler.onPTTStart(PTTMode.SINGLE)
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

    suspend fun onKeyUp() {
        val now = System.currentTimeMillis()
        val holdDuration = now - currentPressTime
        
        when (state) {
            PTTState.FIRST_DOWN -> {
                timeoutJob?.cancel()
                
                if (holdDuration < shortClickThreshold.inWholeMilliseconds) {
                    // Quick press, waiting for second
                    state = PTTState.WAITING_SECOND_DOWN
                    
                    timeoutJob = coroutineScope.launch {
                        delay(doubleClickWindow)
                        if (state == PTTState.WAITING_SECOND_DOWN) {
                            state = PTTState.IDLE
                            handler.onSingleClick()
                        }
                    }
                } else {
                    // This was single hold, but released
                    state = PTTState.IDLE
                }
            }
            
            PTTState.SECOND_DOWN -> {
                timeoutJob?.cancel()
                
                if (holdDuration < shortClickThreshold.inWholeMilliseconds) {
                    // Quick double click
                    state = PTTState.IDLE
                    handler.onDoubleClick()
                } else {
                    // This was double hold, but released
                    state = PTTState.IDLE
                }
            }
            
            PTTState.SINGLE_HOLDING -> {
                state = PTTState.IDLE
                handler.onPTTStop(PTTMode.SINGLE)
            }
            
            PTTState.DOUBLE_HOLDING -> {
                state = PTTState.IDLE
                handler.onPTTStop(PTTMode.DOUBLE)
            }
            
            else -> {
                // In other states ignore UP events
            }
        }
    }
    
    fun reset() {
        timeoutJob?.cancel()
        state = PTTState.IDLE
    }
}

enum class PTTState {
    IDLE,
    FIRST_DOWN,
    WAITING_SECOND_DOWN,
    SECOND_DOWN,
    SINGLE_HOLDING,
    DOUBLE_HOLDING
}

enum class PTTMode { 
    SINGLE, 
    DOUBLE 
}

interface PTTGestureHandler {
    suspend fun onSingleClick()
    suspend fun onDoubleClick() 
    suspend fun onPTTStart(mode: PTTMode)
    suspend fun onPTTStop(mode: PTTMode)
}