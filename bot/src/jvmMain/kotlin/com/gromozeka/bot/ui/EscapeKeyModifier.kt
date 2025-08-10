package com.gromozeka.bot.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.*

fun Modifier.onEscape(
    onEscape: () -> Unit,
): Modifier = composed {
    this.onKeyEvent { event ->
        if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
            onEscape()
            true
        } else {
            false
        }
    }
}