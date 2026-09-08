package com.gromozeka.presentation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent
import com.gromozeka.domain.model.KeyboardShortcutKey

internal actual fun KeyEvent.platformKeyboardShortcutKey(): KeyboardShortcutKey? = null

@Composable
internal actual fun ObservePlatformKeyReleases(
    onRelease: (KeyboardShortcutKey) -> Unit,
    onFocusLost: () -> Unit,
) = Unit
