package com.gromozeka.presentation.ui

import androidx.compose.ui.input.key.KeyEvent
import com.gromozeka.domain.model.KeyboardShortcutKey

internal actual fun KeyEvent.platformKeyboardShortcutKey(): KeyboardShortcutKey? = null
