package com.gromozeka.presentation.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.gromozeka.presentation.services.PTTEventRouter
import com.gromozeka.presentation.services.UnifiedGestureDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun Modifier.advancedPttGestures(
    pttEventRouter: PTTEventRouter,
    coroutineScope: CoroutineScope,
): Modifier = composed {
    val gestureDetector = remember { UnifiedGestureDetector(pttEventRouter, coroutineScope) }

    this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(pass = PointerEventPass.Initial)

            coroutineScope.launch {
                gestureDetector.onGestureDown()
            }

            // Wait for release
            waitForUpOrCancellation(pass = PointerEventPass.Initial)

            coroutineScope.launch {
                gestureDetector.onGestureUp()
            }
        }
    }
}