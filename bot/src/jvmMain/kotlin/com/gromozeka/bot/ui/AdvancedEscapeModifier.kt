package com.gromozeka.bot.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.*
import com.gromozeka.bot.services.PTTEventRouter
import com.gromozeka.bot.services.UnifiedGestureDetector
import kotlinx.coroutines.launch

fun Modifier.advancedEscape(
    pttEventRouter: PTTEventRouter
): Modifier = composed {
    val coroutineScope = rememberCoroutineScope()
    val gestureDetector = remember { UnifiedGestureDetector(pttEventRouter, coroutineScope) }
    
    this.onKeyEvent { event ->
        if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
            coroutineScope.launch {
                gestureDetector.onGestureDown()
            }
            true
        } else if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
            coroutineScope.launch {
                gestureDetector.onGestureUp()
            }
            true
        } else {
            false
        }
    }
}