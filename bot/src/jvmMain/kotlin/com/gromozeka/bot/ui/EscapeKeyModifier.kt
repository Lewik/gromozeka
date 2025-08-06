package com.gromozeka.bot.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.*
import com.gromozeka.bot.services.UnifiedPTTHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun Modifier.onEscape(
    handler: UnifiedPTTHandler,
    coroutineScope: CoroutineScope,
    doubleClickThreshold: Long = 300L
): Modifier = composed {
    var lastEscapeTime by remember { mutableStateOf(0L) }
    
    this.onKeyEvent { event ->
        if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
            val currentTime = System.currentTimeMillis()
            val isDoubleEscape = (currentTime - lastEscapeTime) < doubleClickThreshold
            lastEscapeTime = currentTime
            
            coroutineScope.launch {
                if (isDoubleEscape) {
                    println("[GROMOZEKA] Double Escape detected - stopping TTS and interrupting Claude")
                    handler.onDoubleClick()
                } else {
                    println("[GROMOZEKA] Single Escape detected - stopping TTS")
                    handler.onSingleClick()
                }
            }
            true
        } else {
            false
        }
    }
}