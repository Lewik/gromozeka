package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class KeyboardShortcutSettings(
    val bindings: List<KeyboardShortcutBinding> = defaultBindings(),
    val enterKeyAction: EnterKeyAction = EnterKeyAction.NEW_LINE,
) {
    fun binding(action: KeyboardShortcutAction): KeyboardShortcutBinding =
        bindings.lastOrNull { it.action == action }
            ?: defaultBindings().first { it.action == action }

    fun normalized(): KeyboardShortcutSettings = copy(
        bindings = KeyboardShortcutAction.entries.map(::binding),
    )

    companion object {
        fun defaultBindings(): List<KeyboardShortcutBinding> = listOf(
            KeyboardShortcutBinding(
                action = KeyboardShortcutAction.PUSH_TO_TALK,
                scope = KeyboardShortcutScope.FOCUSED,
                key = KeyboardShortcutKey.ESCAPE,
                enabled = false,
                consumeEvent = true,
            ),
            KeyboardShortcutBinding(
                action = KeyboardShortcutAction.TOGGLE_LIVE_VOICE,
                scope = KeyboardShortcutScope.FOCUSED,
                key = KeyboardShortcutKey.V,
                modifiers = setOf(
                    KeyboardShortcutModifier.CONTROL,
                    KeyboardShortcutModifier.ALT,
                    KeyboardShortcutModifier.META,
                ),
                enabled = false,
            ),
            KeyboardShortcutBinding(
                action = KeyboardShortcutAction.FIX_CLIPBOARD_TEXT,
                scope = KeyboardShortcutScope.FOCUSED,
                key = KeyboardShortcutKey.F,
                modifiers = setOf(
                    KeyboardShortcutModifier.CONTROL,
                    KeyboardShortcutModifier.ALT,
                    KeyboardShortcutModifier.META,
                ),
            ),
            KeyboardShortcutBinding(
                action = KeyboardShortcutAction.TRANSLATE_CLIPBOARD_TEXT,
                scope = KeyboardShortcutScope.FOCUSED,
                key = KeyboardShortcutKey.T,
                modifiers = setOf(
                    KeyboardShortcutModifier.CONTROL,
                    KeyboardShortcutModifier.ALT,
                    KeyboardShortcutModifier.META,
                ),
            ),
            KeyboardShortcutBinding(
                action = KeyboardShortcutAction.EDIT_LAST_USER_MESSAGE,
                scope = KeyboardShortcutScope.FOCUSED,
                key = KeyboardShortcutKey.ARROW_UP,
            ),
            KeyboardShortcutBinding(
                action = KeyboardShortcutAction.NEW_CONVERSATION,
                scope = KeyboardShortcutScope.FOCUSED,
                key = KeyboardShortcutKey.T,
                modifiers = setOf(KeyboardShortcutModifier.META),
            ),
        )
    }
}

@Serializable
enum class EnterKeyAction {
    NEW_LINE,
    SEND_MESSAGE;

    val sendModifiers: Set<KeyboardShortcutModifier>
        get() = when (this) {
            NEW_LINE -> setOf(KeyboardShortcutModifier.SHIFT)
            SEND_MESSAGE -> emptySet()
        }
}

@Serializable
data class KeyboardShortcutBinding(
    val action: KeyboardShortcutAction,
    val scope: KeyboardShortcutScope,
    val key: KeyboardShortcutKey,
    val modifiers: Set<KeyboardShortcutModifier> = emptySet(),
    val enabled: Boolean = true,
    val consumeEvent: Boolean = true,
)

@Serializable
enum class KeyboardShortcutAction(
    val activation: KeyboardShortcutActivation,
    val supportedScopes: Set<KeyboardShortcutScope>,
) {
    PUSH_TO_TALK(
        activation = KeyboardShortcutActivation.HOLD,
        supportedScopes = KeyboardShortcutScope.entries.toSet(),
    ),
    TOGGLE_LIVE_VOICE(
        activation = KeyboardShortcutActivation.ACTIVATE,
        supportedScopes = KeyboardShortcutScope.entries.toSet(),
    ),
    FIX_CLIPBOARD_TEXT(
        activation = KeyboardShortcutActivation.ACTIVATE,
        supportedScopes = KeyboardShortcutScope.entries.toSet(),
    ),
    TRANSLATE_CLIPBOARD_TEXT(
        activation = KeyboardShortcutActivation.ACTIVATE,
        supportedScopes = KeyboardShortcutScope.entries.toSet(),
    ),
    EDIT_LAST_USER_MESSAGE(
        activation = KeyboardShortcutActivation.ACTIVATE,
        supportedScopes = setOf(KeyboardShortcutScope.FOCUSED),
    ),
    NEW_CONVERSATION(
        activation = KeyboardShortcutActivation.ACTIVATE,
        supportedScopes = setOf(KeyboardShortcutScope.FOCUSED),
    ),
}

@Serializable
enum class KeyboardShortcutActivation {
    ACTIVATE,
    HOLD,
}

@Serializable
enum class KeyboardShortcutScope {
    FOCUSED,
    GLOBAL,
}

@Serializable
enum class KeyboardShortcutModifier {
    CONTROL,
    ALT,
    SHIFT,
    META,
}

@Serializable
enum class KeyboardShortcutKey {
    A,
    B,
    C,
    D,
    E,
    F,
    G,
    H,
    I,
    J,
    K,
    L,
    M,
    N,
    O,
    P,
    Q,
    R,
    S,
    T,
    U,
    V,
    W,
    X,
    Y,
    Z,
    DIGIT_0,
    DIGIT_1,
    DIGIT_2,
    DIGIT_3,
    DIGIT_4,
    DIGIT_5,
    DIGIT_6,
    DIGIT_7,
    DIGIT_8,
    DIGIT_9,
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,
    F13,
    F14,
    F15,
    F16,
    F17,
    F18,
    F19,
    F20,
    F21,
    F22,
    F23,
    F24,
    ESCAPE,
    SPACE,
    ENTER,
    TAB,
    BACKSPACE,
    DELETE,
    ARROW_UP,
    ARROW_DOWN,
    ARROW_LEFT,
    ARROW_RIGHT,
    HOME,
    END,
    PAGE_UP,
    PAGE_DOWN,
}

enum class KeyboardShortcutValidationSeverity {
    ERROR,
    WARNING,
}

data class KeyboardShortcutValidationIssue(
    val action: KeyboardShortcutAction,
    val severity: KeyboardShortcutValidationSeverity,
    val message: String,
)

object KeyboardShortcutValidator {
    fun validate(settings: KeyboardShortcutSettings): List<KeyboardShortcutValidationIssue> {
        val bindings = settings.normalized().bindings
        return buildList {
            bindings.forEach { binding ->
                if (binding.scope !in binding.action.supportedScopes) {
                    add(
                        KeyboardShortcutValidationIssue(
                            action = binding.action,
                            severity = KeyboardShortcutValidationSeverity.ERROR,
                            message = "${binding.action} does not support ${binding.scope} shortcuts",
                        )
                    )
                }
                if (
                    binding.key == KeyboardShortcutKey.ENTER &&
                    binding.modifiers in listOf(emptySet(), setOf(KeyboardShortcutModifier.SHIFT))
                ) {
                    add(
                        KeyboardShortcutValidationIssue(
                            action = binding.action,
                            severity = KeyboardShortcutValidationSeverity.ERROR,
                            message = "Enter and Shift+Enter are reserved for sending messages and inserting new lines",
                        )
                    )
                }
            }

            bindings.groupBy { it.key to it.modifiers }
                .values
                .filter { it.size > 1 }
                .forEach { conflicts ->
                    conflicts.forEach { binding ->
                        add(
                            KeyboardShortcutValidationIssue(
                                action = binding.action,
                                severity = KeyboardShortcutValidationSeverity.ERROR,
                                message = "Shortcut conflicts with ${conflicts.filterNot { it.action == binding.action }.joinToString { it.action.name }}",
                            )
                        )
                    }
                }
        }
    }
}
