package com.gromozeka.presentation.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.gromozeka.presentation.services.PttEventHandler
import com.gromozeka.presentation.services.UnifiedGestureDetector
import kotlinx.coroutines.CoroutineScope

fun Modifier.advancedPttGestures(
    pttEventRouter: PttEventHandler,
    coroutineScope: CoroutineScope,
): Modifier = composed {
    val gestureDetector = remember(pttEventRouter, coroutineScope) {
        UnifiedGestureDetector(pttEventRouter, coroutineScope)
    }

    this.pointerInput(gestureDetector) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            gestureDetector.onGestureDown()

            var gestureFinished = false
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial)
                        .changes
                        .firstOrNull { it.id == down.id }
                    if (change == null) {
                        gestureDetector.cancelGesture()
                        gestureFinished = true
                        break
                    }
                    if (!change.pressed) {
                        gestureDetector.onGestureUp()
                        gestureFinished = true
                        break
                    }
                }
            } finally {
                if (!gestureFinished) gestureDetector.cancelGesture()
            }
        }
    }
}
