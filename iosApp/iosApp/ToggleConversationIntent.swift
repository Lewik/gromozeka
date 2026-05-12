import AppIntents
import Foundation

struct ToggleConversationIntent: AppIntent {
    static var title: LocalizedStringResource = "Toggle Gromozeka Conversation"
    static var description = IntentDescription("Starts or stops the current Gromozeka voice conversation.")
    static var openAppWhenRun = true

    func perform() async throws -> some IntentResult {
        let defaults = UserDefaults.standard
        let nextActive = !defaults.bool(forKey: ActionButtonDefaults.activeKey)
        defaults.set(nextActive, forKey: ActionButtonDefaults.activeKey)
        defaults.set(defaults.integer(forKey: ActionButtonDefaults.counterKey) + 1, forKey: ActionButtonDefaults.counterKey)
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
    }
}

private enum ActionButtonDefaults {
    static let activeKey = "gromozeka.actionButton.active"
    static let counterKey = "gromozeka.actionButton.counter"
}
