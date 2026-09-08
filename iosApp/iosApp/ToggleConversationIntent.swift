import AppIntents
import Foundation
import GromozekaPresentation

struct ToggleConversationIntent: AppIntent {
    static var title: LocalizedStringResource = "native.shortcuts.toggleRecording"
    static var description = IntentDescription("native.shortcuts.toggleRecordingDescription")
    static var openAppWhenRun = true

    func perform() async throws -> some IntentResult {
        let defaults = UserDefaults.standard
        let nextCommand = defaults.integer(forKey: ActionButtonDefaults.commandCounterKey) + 1
        defaults.set(nextCommand, forKey: ActionButtonDefaults.commandCounterKey)
        return .result()
    }
}

struct FixTextIntent: AppIntent {
    static var title: LocalizedStringResource = "quickText.fix"
    static var description = IntentDescription("quickText.fixDescription")
    static var openAppWhenRun = false

    @Parameter(title: "native.shortcuts.textParameter")
    var text: String

    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        let result = try await runQuickTextAction(
            actionId: "fix_text_preserve_language",
            text: text
        )
        return .result(value: result)
    }
}

struct TranslateTextIntent: AppIntent {
    static var title: LocalizedStringResource = "quickText.translate"
    static var description = IntentDescription("quickText.translateDescription")
    static var openAppWhenRun = false

    @Parameter(title: "native.shortcuts.textParameter")
    var text: String

    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        let result = try await runQuickTextAction(
            actionId: "translate_interface_language",
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
                continuation.resume(throwing: QuickTextActionError(message: error ?? String(localized: "common.unknownError")))
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
