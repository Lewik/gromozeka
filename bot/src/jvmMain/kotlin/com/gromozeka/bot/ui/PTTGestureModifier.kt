package com.gromozeka.bot.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.gromozeka.bot.services.PTTGestureDetector
import com.gromozeka.bot.services.PTTGestureHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun Modifier.pttGestures(
    handler: PTTGestureHandler,
    coroutineScope: CoroutineScope
): Modifier {
    val gestureDetector = PTTGestureDetector(handler, coroutineScope)
    
    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            
            // Уведомляем о нажатии
            coroutineScope.launch {
                gestureDetector.onKeyDown()
            }
            
            // Ждем отпускания
            val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
            
            // Уведомляем об отпускании
            coroutineScope.launch {
                gestureDetector.onKeyUp()
            }
        }
    }
}