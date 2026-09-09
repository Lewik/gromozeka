// Generated from localization/*.json by scripts/generate-native-localization.py.
import AppIntents

struct GromozekaShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: ToggleConversationIntent(),
            phrases: [
                "Toggle voice recording in \(.applicationName)",
                "Start voice recording in \(.applicationName)"
            ],
            shortTitle: "native.shortcuts.toggleRecording",
            systemImageName: "mic.circle"
        )
        AppShortcut(
            intent: FixTextIntent(),
            phrases: [
                "Fix text with \(.applicationName)",
                "Correct text with \(.applicationName)"
            ],
            shortTitle: "quickText.fix",
            systemImageName: "text.badge.checkmark"
        )
        AppShortcut(
            intent: TranslateTextIntent(),
            phrases: [
                "Translate text with \(.applicationName)",
                "Translate with \(.applicationName)"
            ],
            shortTitle: "quickText.translate",
            systemImageName: "translate"
        )
    }
}

extension FixTextIntent {
    static var parameterSummary: some ParameterSummary {
        Summary("Fix \(\.$text)")
    }
}

extension TranslateTextIntent {
    static var parameterSummary: some ParameterSummary {
        Summary("Translate \(\.$text)")
    }
}
