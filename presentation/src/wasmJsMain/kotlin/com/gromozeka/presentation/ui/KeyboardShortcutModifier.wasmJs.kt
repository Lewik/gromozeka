package com.gromozeka.presentation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.KeyEvent
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import com.gromozeka.domain.model.KeyboardShortcutKey

internal actual fun KeyEvent.platformKeyboardShortcutKey(): KeyboardShortcutKey? = null

@Composable
internal actual fun ObservePlatformKeyReleases(
    onRelease: (KeyboardShortcutKey) -> Unit,
    onFocusLost: () -> Unit,
) {
    val currentRelease by rememberUpdatedState(onRelease)
    val currentFocusLost by rememberUpdatedState(onFocusLost)
    DisposableEffect(Unit) {
        val pendingFrames = mutableSetOf<Int>()
        val releaseListener: (Event) -> Unit = listener@{ event ->
            val key = (event as KeyboardEvent).code.toShortcutKey() ?: return@listener
            var frame = 0
            // Compose Web drops native input key-up events; wait for its queued key-down processing.
            frame = window.requestAnimationFrame {
                pendingFrames.remove(frame)
                currentRelease(key)
            }
            pendingFrames.add(frame)
        }
        val blurListener: (Event) -> Unit = { currentFocusLost() }
        document.addEventListener("keyup", releaseListener, true)
        window.addEventListener("blur", blurListener)
        onDispose {
            document.removeEventListener("keyup", releaseListener, true)
            window.removeEventListener("blur", blurListener)
            pendingFrames.forEach(window::cancelAnimationFrame)
        }
    }
}

private fun String.toShortcutKey(): KeyboardShortcutKey? {
    val name = when {
        startsWith("Key") -> removePrefix("Key")
        startsWith("Digit") -> "DIGIT_" + removePrefix("Digit")
        startsWith("Arrow") -> "ARROW_" + removePrefix("Arrow").uppercase()
        this == "NumpadEnter" -> "ENTER"
        this == "PageUp" -> "PAGE_UP"
        this == "PageDown" -> "PAGE_DOWN"
        else -> uppercase()
    }
    return KeyboardShortcutKey.entries.firstOrNull { it.name == name }
}
