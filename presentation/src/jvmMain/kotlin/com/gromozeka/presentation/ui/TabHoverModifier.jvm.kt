package com.gromozeka.presentation.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

internal actual fun Modifier.onTabHover(
    onEnter: () -> Unit,
    onExit: () -> Unit,
): Modifier =
    onPointerEvent(PointerEventType.Enter) {
        onEnter()
    }.onPointerEvent(PointerEventType.Exit) {
        onExit()
    }
