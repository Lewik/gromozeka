import AppIntents
import Foundation
import GromozekaPresentation

struct ToggleConversationIntent: AppIntent {
    static var title: LocalizedStringResource = "Toggle Gromozeka Conversation"
    static var description = IntentDescription("Starts or stops the current Gromozeka voice conversation.")
    static var openAppWhenRun = true

    func perform() async throws -> some IntentResult {
        let defaults = UserDefaults.standard
        let nextCommand = defaults.integer(forKey: ActionButtonDefaults.commandCounterKey) + 1
        defaults.set(nextCommand, forKey: ActionButtonDefaults.commandCounterKey)
        return .result()
    }
}

struct GromozekaShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: ToggleConversationIntent(),
            phrases: [
                "Toggle Gromozeka conversation in \(.applicationName)",
                "Start Gromozeka conversation in \(.applicationName)"
            ],
            shortTitle: "Toggle Conversation",
            systemImageName: "mic.circle"
        )
        AppShortcut(
            intent: FixTextIntent(),
            phrases: [
                "Fix text with \(.applicationName)",
                "Correct text with \(.applicationName)"
            ],
            shortTitle: "Fix Text",
            systemImageName: "text.badge.checkmark"
        )
        AppShortcut(
            intent: TranslateTextIntent(),
            phrases: [
                "Translate text with \(.applicationName)",
                "Translate with \(.applicationName)"
            ],
            shortTitle: "Translate",
            systemImageName: "translate"
        )
    }
}

struct FixTextIntent: AppIntent {
    static var title: LocalizedStringResource = "Fix Text"
    static var description = IntentDescription("Corrects spelling, grammar, punctuation, and wording while preserving the original language.")
    static var openAppWhenRun = false

    @Parameter(title: "Text")
    var text: String

    static var parameterSummary: some ParameterSummary {
        Summary("Fix \(\.$text)")
    }

    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        let result = try await runQuickTextAction(
            actionId: "fix_text_preserve_language",
            text: text
        )
        return .result(value: result)
    }
}

struct TranslateTextIntent: AppIntent {
    static var title: LocalizedStringResource = "Translate Text"
    static var description = IntentDescription("Translates Russian text to English and non-Russian text to Russian.")
    static var openAppWhenRun = false

    @Parameter(title: "Text")
    var text: String

    static var parameterSummary: some ParameterSummary {
        Summary("Translate \(\.$text)")
    }

    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        let result = try await runQuickTextAction(
            actionId: "translate_ru_en",
            text: text
        )
        return .result(value: result)
    }
}

private func runQuickTextAction(actionId: String, text: String) async throws -> String {
    try await withCheckedThrowingContinuation { continuation in
        IosQuickTextActionsKt.runIosQuickTextAction(
            actionId: actionId,
            text: text
        ) { result, error in
            if let result {
                continuation.resume(returning: result)
            } else {
                continuation.resume(throwing: QuickTextActionError(message: error ?? "Quick text action failed"))
            }
        }
    }
}

private struct QuickTextActionError: LocalizedError {
    let message: String

    var errorDescription: String? {
        message
    }
}

private enum ActionButtonDefaults {
    static let commandCounterKey = "gromozeka.actionButton.commandCounter"
}
