import AppIntents
import GromozekaMobileWorker

struct SendGromozekaEventIntent: AppIntent {
    static let title: LocalizedStringResource = "Send Gromozeka Event"
    static let description = IntentDescription("Stores a named event and synchronizes it with Gromozeka.")
    static let openAppWhenRun = false

    @Parameter(title: "Event name")
    var eventName: String

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let name = eventName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else {
            throw MobileWorkerIntentError.emptyName
        }
        let runtime = MobileWorkerRuntimeHost.shared.runtime
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            runtime.recordCustomTrigger(
                name: name,
                attributes: [:],
                observedAtEpochMilliseconds: Date().epochMilliseconds,
                completionHandler: { error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else {
                        continuation.resume()
                    }
                }
            )
        }
        let synchronized = await withCheckedContinuation { continuation in
            runtime.synchronize { _, error in
                continuation.resume(returning: error == nil)
            }
        }
        let message = synchronized
            ? "Sent \(name) to Gromozeka"
            : "Stored \(name); it will synchronize when the Server is available"
        return .result(dialog: IntentDialog(stringLiteral: message))
    }
}

struct GromozekaWorkerShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: SendGromozekaEventIntent(),
            phrases: ["Send an event to \(.applicationName)"],
            shortTitle: "Send event",
            systemImageName: "bolt.horizontal.circle"
        )
    }
}

private enum MobileWorkerIntentError: LocalizedError {
    case emptyName

    var errorDescription: String? {
        "Event name cannot be empty"
    }
}
