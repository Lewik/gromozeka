package com.gromozeka.presentation.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.*
import com.gromozeka.presentation.services.PTTEventRouter
import com.gromozeka.presentation.services.UnifiedGestureDetector
import kotlinx.coroutines.launch

fun Modifier.advancedEscape(
    pttEventRouter: PTTEventRouter,
): Modifier = composed {
    val coroutineScope = rememberCoroutineScope()
    val gestureDetector = remember { UnifiedGestureDetector(pttEventRouter, coroutineScope) }

    this.onKeyEvent { event ->
        if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
            // Escape should only reset PTT state, not start new PTT
            coroutineScope.launch {
                gestureDetector.resetGestureState()
            }
            true
        } else {
            false
        }
    }
}