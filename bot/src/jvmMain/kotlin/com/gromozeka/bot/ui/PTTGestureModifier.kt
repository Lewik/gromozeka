package com.gromozeka.bot.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

fun Modifier.pttGestures(
    onPressed: () -> Unit,
    onReleased: () -> Unit
): Modifier {
    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            
            // Notify about press
            onPressed()
            
            // Wait for release
            val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
            
            // Notify about release
            onReleased()
        }
    }
}