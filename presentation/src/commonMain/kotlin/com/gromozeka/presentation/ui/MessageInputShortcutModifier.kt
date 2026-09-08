package com.gromozeka.presentation.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import com.gromozeka.domain.model.EnterKeyAction
import com.gromozeka.domain.model.KeyboardShortcutBinding
import com.gromozeka.domain.model.KeyboardShortcutKey

internal fun Modifier.messageInputShortcuts(
    enterKeyAction: EnterKeyAction,
    isComposing: () -> Boolean,
    isEmpty: () -> Boolean,
    editLastMessageShortcut: KeyboardShortcutBinding?,
    onEditLastUserMessage: () -> Boolean,
    onSubmit: () -> Unit,
): Modifier = composed {
    var sendPressed by remember { mutableStateOf(false) }
    ObservePlatformKeyReleases(
        onRelease = { if (it == KeyboardShortcutKey.ENTER) sendPressed = false },
        onFocusLost = { sendPressed = false },
    )
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) {
        if (!windowFocused) sendPressed = false
    }
    onFocusChanged { if (!it.isFocused) sendPressed = false }
        .onPreviewKeyEvent { event ->
            val key = event.toKeyboardShortcutKey()
            if (key == KeyboardShortcutKey.ENTER && sendPressed) {
                if (event.type == KeyEventType.KeyUp) sendPressed = false
                return@onPreviewKeyEvent true
            }
            if (isComposing()) return@onPreviewKeyEvent false
            if (
                key == KeyboardShortcutKey.ENTER &&
                event.keyboardShortcutModifiers() == enterKeyAction.sendModifiers
            ) {
                if (event.type == KeyEventType.KeyDown) {
                    sendPressed = true
                    onSubmit()
                }
                return@onPreviewKeyEvent true
            }
            if (editLastMessageShortcut != null && isEmpty() && event.matches(editLastMessageShortcut)) {
                if (event.type == KeyEventType.KeyDown) onEditLastUserMessage()
                return@onPreviewKeyEvent editLastMessageShortcut.consumeEvent
            }
            false
        }
}
