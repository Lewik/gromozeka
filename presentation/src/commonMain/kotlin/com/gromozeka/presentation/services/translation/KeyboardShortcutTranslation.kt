package com.gromozeka.presentation.services.translation

import com.gromozeka.domain.model.KeyboardShortcutAction
import com.gromozeka.domain.model.KeyboardShortcutScope
import com.gromozeka.domain.model.KeyboardShortcutValidationCode
import com.gromozeka.domain.model.KeyboardShortcutValidationIssue

fun KeyboardShortcutValidationIssue.localizedText(): LocalizedText = when (code) {
    KeyboardShortcutValidationCode.UNSUPPORTED_SCOPE -> localizedText(
        "settingsUi.shortcutUnsupportedScope",
        "action" to action.localizedText(),
        "scope" to requireNotNull(scope).localizedText(),
    )
    KeyboardShortcutValidationCode.COMPOSER_ENTER_RESERVED -> localizedText("settingsUi.shortcutComposerEnterReserved")
    KeyboardShortcutValidationCode.CONFLICT -> localizedText(
        "settingsUi.shortcutConflict",
        "actions" to LocalizedText.Joined(conflictingActions.map { it.localizedText() }),
    )
}

fun KeyboardShortcutScope.localizedText(): LocalizedText = localizedText(when (this) {
    KeyboardShortcutScope.FOCUSED -> "settingsUi.keyboardScopeFocused"
    KeyboardShortcutScope.GLOBAL -> "settingsUi.keyboardScopeGlobal"
})

fun KeyboardShortcutAction.localizedText(): LocalizedText = localizedText(when (this) {
    KeyboardShortcutAction.PUSH_TO_TALK -> "settingsUi.pushToTalk"
    KeyboardShortcutAction.TOGGLE_LIVE_VOICE -> "settingsUi.toggleContinuousVoice"
    KeyboardShortcutAction.FIX_CLIPBOARD_TEXT -> "settingsUi.fixClipboardText"
    KeyboardShortcutAction.TRANSLATE_CLIPBOARD_TEXT -> "settingsUi.translateClipboardText"
    KeyboardShortcutAction.EDIT_LAST_USER_MESSAGE -> "settingsUi.editPreviousMessage"
    KeyboardShortcutAction.NEW_CONVERSATION -> "settingsUi.newConversation"
})
