package com.gromozeka.presentation.ui

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import com.gromozeka.domain.model.KeyboardShortcutKey
import java.awt.event.KeyEvent as AwtKeyEvent

internal actual fun KeyEvent.platformKeyboardShortcutKey(): KeyboardShortcutKey? = when (key.keyCode.toInt()) {
    AwtKeyEvent.VK_F13 -> KeyboardShortcutKey.F13
    AwtKeyEvent.VK_F14 -> KeyboardShortcutKey.F14
    AwtKeyEvent.VK_F15 -> KeyboardShortcutKey.F15
    AwtKeyEvent.VK_F16 -> KeyboardShortcutKey.F16
    AwtKeyEvent.VK_F17 -> KeyboardShortcutKey.F17
    AwtKeyEvent.VK_F18 -> KeyboardShortcutKey.F18
    AwtKeyEvent.VK_F19 -> KeyboardShortcutKey.F19
    AwtKeyEvent.VK_F20 -> KeyboardShortcutKey.F20
    AwtKeyEvent.VK_F21 -> KeyboardShortcutKey.F21
    AwtKeyEvent.VK_F22 -> KeyboardShortcutKey.F22
    AwtKeyEvent.VK_F23 -> KeyboardShortcutKey.F23
    AwtKeyEvent.VK_F24 -> KeyboardShortcutKey.F24
    else -> null
}
