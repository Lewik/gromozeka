package com.gromozeka.presentation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.gromozeka.domain.model.KeyboardShortcutAction
import com.gromozeka.domain.model.KeyboardShortcutActivation
import com.gromozeka.domain.model.KeyboardShortcutBinding
import com.gromozeka.domain.model.KeyboardShortcutKey
import com.gromozeka.domain.model.KeyboardShortcutModifier
import com.gromozeka.domain.model.KeyboardShortcutScope
import com.gromozeka.domain.model.KeyboardShortcutSettings
import com.gromozeka.domain.model.KeyboardShortcutValidationSeverity
import com.gromozeka.domain.model.KeyboardShortcutValidator
import com.gromozeka.presentation.services.HoldToTalkShortcutController

fun Modifier.focusedKeyboardShortcuts(
    settings: KeyboardShortcutSettings,
    globalShortcutsAreLocal: Boolean = false,
    enabled: Boolean = true,
    holdToTalkController: HoldToTalkShortcutController,
    onActivate: (KeyboardShortcutAction) -> Unit,
): Modifier = composed {
    val normalized = remember(settings) { settings.normalized() }
    val invalidActions = remember(normalized) {
        KeyboardShortcutValidator.validate(normalized)
            .filter { it.severity == KeyboardShortcutValidationSeverity.ERROR }
            .mapTo(mutableSetOf()) { it.action }
    }
    val pressedActions = remember { mutableMapOf<KeyboardShortcutKey, KeyboardShortcutBinding>() }
    ObservePlatformKeyReleases(
        onRelease = { key ->
            val released = pressedActions.remove(key)
            if (released?.action?.activation == KeyboardShortcutActivation.HOLD) {
                holdToTalkController.onReleased()
            }
        },
        onFocusLost = {
            if (pressedActions.values.any { it.action == KeyboardShortcutAction.PUSH_TO_TALK }) {
                holdToTalkController.cancel()
            }
            pressedActions.clear()
        },
    )
    val windowFocused = LocalWindowInfo.current.isWindowFocused

    LaunchedEffect(windowFocused) {
        if (!windowFocused) {
            if (pressedActions.values.any { it.action == KeyboardShortcutAction.PUSH_TO_TALK }) {
                holdToTalkController.cancel()
            }
            pressedActions.clear()
        }
    }

    DisposableEffect(normalized, enabled) {
        onDispose {
            if (pressedActions.values.any { it.action == KeyboardShortcutAction.PUSH_TO_TALK }) {
                holdToTalkController.cancel()
            }
            pressedActions.clear()
        }
    }

    onPreviewKeyEvent { event ->
        if (!enabled) return@onPreviewKeyEvent false
        val key = event.toKeyboardShortcutKey() ?: return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyUp) {
            val released = pressedActions.remove(key) ?: return@onPreviewKeyEvent false
            if (released.action.activation == KeyboardShortcutActivation.HOLD) {
                holdToTalkController.onReleased()
            }
            return@onPreviewKeyEvent released.consumeEvent
        }
        val binding = normalized.bindings.firstOrNull { candidate ->
            candidate.enabled &&
                (candidate.scope == KeyboardShortcutScope.FOCUSED || globalShortcutsAreLocal) &&
                candidate.action != KeyboardShortcutAction.EDIT_LAST_USER_MESSAGE &&
                candidate.action !in invalidActions &&
                event.matches(candidate)
        } ?: return@onPreviewKeyEvent false

        if (event.type == KeyEventType.KeyDown && pressedActions.put(key, binding) == null) {
            when (binding.action.activation) {
                KeyboardShortcutActivation.HOLD -> holdToTalkController.onPressed()
                KeyboardShortcutActivation.ACTIVATE -> onActivate(binding.action)
            }
        }
        binding.consumeEvent
    }
}

fun KeyEvent.matches(binding: KeyboardShortcutBinding): Boolean =
    toKeyboardShortcutKey() == binding.key && keyboardShortcutModifiers() == binding.modifiers

fun KeyEvent.toKeyboardShortcutKey(): KeyboardShortcutKey? =
    key.toKeyboardShortcutKey() ?: platformKeyboardShortcutKey()

internal expect fun KeyEvent.platformKeyboardShortcutKey(): KeyboardShortcutKey?

fun KeyEvent.keyboardShortcutModifiers(): Set<KeyboardShortcutModifier> = buildSet {
    if (isCtrlPressed) add(KeyboardShortcutModifier.CONTROL)
    if (isAltPressed) add(KeyboardShortcutModifier.ALT)
    if (isShiftPressed) add(KeyboardShortcutModifier.SHIFT)
    if (isMetaPressed) add(KeyboardShortcutModifier.META)
}

fun Key.toKeyboardShortcutKey(): KeyboardShortcutKey? = when (this) {
    Key.A -> KeyboardShortcutKey.A
    Key.B -> KeyboardShortcutKey.B
    Key.C -> KeyboardShortcutKey.C
    Key.D -> KeyboardShortcutKey.D
    Key.E -> KeyboardShortcutKey.E
    Key.F -> KeyboardShortcutKey.F
    Key.G -> KeyboardShortcutKey.G
    Key.H -> KeyboardShortcutKey.H
    Key.I -> KeyboardShortcutKey.I
    Key.J -> KeyboardShortcutKey.J
    Key.K -> KeyboardShortcutKey.K
    Key.L -> KeyboardShortcutKey.L
    Key.M -> KeyboardShortcutKey.M
    Key.N -> KeyboardShortcutKey.N
    Key.O -> KeyboardShortcutKey.O
    Key.P -> KeyboardShortcutKey.P
    Key.Q -> KeyboardShortcutKey.Q
    Key.R -> KeyboardShortcutKey.R
    Key.S -> KeyboardShortcutKey.S
    Key.T -> KeyboardShortcutKey.T
    Key.U -> KeyboardShortcutKey.U
    Key.V -> KeyboardShortcutKey.V
    Key.W -> KeyboardShortcutKey.W
    Key.X -> KeyboardShortcutKey.X
    Key.Y -> KeyboardShortcutKey.Y
    Key.Z -> KeyboardShortcutKey.Z
    Key.Zero -> KeyboardShortcutKey.DIGIT_0
    Key.One -> KeyboardShortcutKey.DIGIT_1
    Key.Two -> KeyboardShortcutKey.DIGIT_2
    Key.Three -> KeyboardShortcutKey.DIGIT_3
    Key.Four -> KeyboardShortcutKey.DIGIT_4
    Key.Five -> KeyboardShortcutKey.DIGIT_5
    Key.Six -> KeyboardShortcutKey.DIGIT_6
    Key.Seven -> KeyboardShortcutKey.DIGIT_7
    Key.Eight -> KeyboardShortcutKey.DIGIT_8
    Key.Nine -> KeyboardShortcutKey.DIGIT_9
    Key.F1 -> KeyboardShortcutKey.F1
    Key.F2 -> KeyboardShortcutKey.F2
    Key.F3 -> KeyboardShortcutKey.F3
    Key.F4 -> KeyboardShortcutKey.F4
    Key.F5 -> KeyboardShortcutKey.F5
    Key.F6 -> KeyboardShortcutKey.F6
    Key.F7 -> KeyboardShortcutKey.F7
    Key.F8 -> KeyboardShortcutKey.F8
    Key.F9 -> KeyboardShortcutKey.F9
    Key.F10 -> KeyboardShortcutKey.F10
    Key.F11 -> KeyboardShortcutKey.F11
    Key.F12 -> KeyboardShortcutKey.F12
    Key.Escape -> KeyboardShortcutKey.ESCAPE
    Key.Spacebar -> KeyboardShortcutKey.SPACE
    Key.Enter, Key.NumPadEnter -> KeyboardShortcutKey.ENTER
    Key.Tab -> KeyboardShortcutKey.TAB
    Key.Backspace -> KeyboardShortcutKey.BACKSPACE
    Key.Delete -> KeyboardShortcutKey.DELETE
    Key.DirectionUp -> KeyboardShortcutKey.ARROW_UP
    Key.DirectionDown -> KeyboardShortcutKey.ARROW_DOWN
    Key.DirectionLeft -> KeyboardShortcutKey.ARROW_LEFT
    Key.DirectionRight -> KeyboardShortcutKey.ARROW_RIGHT
    Key.MoveHome -> KeyboardShortcutKey.HOME
    Key.MoveEnd -> KeyboardShortcutKey.END
    Key.PageUp -> KeyboardShortcutKey.PAGE_UP
    Key.PageDown -> KeyboardShortcutKey.PAGE_DOWN
    else -> null
}

@Composable
internal expect fun ObservePlatformKeyReleases(
    onRelease: (KeyboardShortcutKey) -> Unit,
    onFocusLost: () -> Unit,
)
