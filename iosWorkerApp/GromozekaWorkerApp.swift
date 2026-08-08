import SwiftUI
import UIKit
import GromozekaMobileWorker

@main
struct GromozekaWorkerApp: App {
    @UIApplicationDelegateAdaptor(MobileWorkerAppDelegate.self) private var appDelegate
    @StateObject private var controller = MobileWorkerController()

    var body: some Scene {
        WindowGroup {
            MobileWorkerView(controller: controller)
                .preferredColorScheme(.dark)
        }
    }
}

private struct MobileWorkerView: View {
    @ObservedObject var controller: MobileWorkerController
    @State private var serverUrl = ""
    @State private var enrollmentToken = ""
    @State private var workerId = defaultWorkerId()
    @State private var usePassword = false
    @State private var showAdvancedEnrollment = false
    @State private var username = ""
    @State private var password = ""

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color(red: 0.035, green: 0.055, blue: 0.05), Color(red: 0.02, green: 0.025, blue: 0.024)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text("GROMOZEKA / MOBILE WORKER")
                        .font(.system(.caption, design: .monospaced, weight: .bold))
                        .foregroundStyle(workerGreen)
                    Text("Device signals,\nstored first.")
                        .font(.system(size: 38, weight: .black, design: .rounded))
                        .tracking(-1.2)
                    Text("Independent from the chat client. Events stay on this device until the server acknowledges them.")
                        .foregroundStyle(.secondary)
                        .font(.body)

                    if let status = controller.status, status.enrolled {
                        enrolledContent(status)
                    } else {
                        enrollmentForm
                    }

                    if let message = controller.signalMessage {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    if let error = controller.error {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(Color(red: 1, green: 0.42, blue: 0.35))
                    }
                }
                .padding(.horizontal, 22)
                .padding(.vertical, 28)
            }
        }
        .tint(workerGreen)
        .onChange(of: controller.status?.enrolled) { _, enrolled in
            if enrolled == true {
                enrollmentToken = ""
                password = ""
            }
        }
    }

    private var enrollmentForm: some View {
        VStack(spacing: 12) {
            workerField("Server URL", text: $serverUrl, prompt: "https://gromozeka.example")
            workerField("Worker ID", text: $workerId, prompt: "family-iphone")

            if let code = controller.connectionCode {
                VStack(spacing: 8) {
                    Text("APPROVE THIS CODE")
                        .font(.system(.caption, design: .monospaced, weight: .bold))
                        .foregroundStyle(workerGreen)
                    Text(code)
                        .font(.system(size: 28, weight: .black, design: .monospaced))
                    Text("Open Settings > Security in an authorized Gromozeka Client.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    Button("Cancel") { controller.cancelConnection() }
                        .buttonStyle(.bordered)
                }
                .frame(maxWidth: .infinity)
                .padding(18)
                .background(Color.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 18))
            } else if usePassword {
                workerField("Username", text: $username, prompt: "username")
                SecureField("Password", text: $password)
                    .textContentType(.password)
                    .workerFieldStyle()
                Button {
                    controller.connectWithPassword(
                        serverUrl: serverUrl,
                        workerId: workerId,
                        username: username,
                        password: password
                    )
                } label: {
                    Text(controller.busy ? "Connecting" : "Connect with password")
                        .frame(maxWidth: .infinity)
                        .fontWeight(.bold)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(
                    controller.busy || serverUrl.isEmpty || workerId.isEmpty ||
                    username.isEmpty || password.isEmpty
                )
            } else {
                Button {
                    controller.startConnection(serverUrl: serverUrl, workerId: workerId)
                } label: {
                    Text(controller.busy ? "Creating code" : "Connect device")
                        .frame(maxWidth: .infinity)
                        .fontWeight(.bold)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(controller.busy || serverUrl.isEmpty || workerId.isEmpty)
            }

            Button(usePassword ? "Use connection code" : "Use username and password") {
                usePassword.toggle()
            }
            .buttonStyle(.plain)
            .foregroundStyle(workerGreen)

            DisclosureGroup("Advanced", isExpanded: $showAdvancedEnrollment) {
                VStack(spacing: 12) {
                    SecureField("One-time enrollment token", text: $enrollmentToken)
                        .textContentType(.oneTimeCode)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .workerFieldStyle()
                    Button {
                        controller.enroll(
                            serverUrl: serverUrl,
                            enrollmentToken: enrollmentToken,
                            workerId: workerId
                        )
                    } label: {
                        Text("Use one-time token").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(
                        controller.busy || serverUrl.isEmpty || enrollmentToken.isEmpty || workerId.isEmpty
                    )
                }
                .padding(.top, 12)
            }
        }
    }

    @ViewBuilder
    private func enrolledContent(_ status: GromozekaMobileWorker.IosMobileWorkerStatus) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("ENROLLED")
                .font(.system(.caption, design: .monospaced, weight: .bold))
                .foregroundStyle(workerGreen)
            Text(status.workerId ?? "")
                .font(.headline)
            Text(status.serverUrl ?? "")
                .font(.footnote.monospaced())
                .foregroundStyle(.secondary)
            HStack {
                Label("\(status.pendingEventCount) pending", systemImage: "tray.full")
                Spacer()
                if let last = status.lastSynchronizedAt {
                    Text(last)
                        .lineLimit(1)
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .padding(18)
        .background(Color.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 18))

        VStack(spacing: 10) {
            Button("Sync now") { controller.synchronize() }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button("Enable location and geofences") {
                controller.signals.requestLocationAuthorization()
            }
            .buttonStyle(.bordered)
            .frame(maxWidth: .infinity, alignment: .leading)
            Button("Enable sleep events") {
                controller.signals.requestSleepAuthorization()
            }
            .buttonStyle(.bordered)
            .frame(maxWidth: .infinity, alignment: .leading)
            Button("Enable Bluetooth state") {
                controller.signals.enableBluetooth()
            }
            .buttonStyle(.bordered)
            .frame(maxWidth: .infinity, alignment: .leading)
            Button("Scan NFC tag") {
                controller.signals.scanNfcTag()
            }
            .buttonStyle(.bordered)
            .frame(maxWidth: .infinity, alignment: .leading)
            Button("Remove from this device", role: .destructive) { controller.reset() }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .disabled(controller.busy)

        MobileWorkerSignalSettingsView(signals: controller.signals)
    }

    private func workerField(_ title: String, text: Binding<String>, prompt: String) -> some View {
        TextField(title, text: text, prompt: Text(prompt))
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(title == "Server URL" ? .URL : .asciiCapable)
            .workerFieldStyle()
    }
}

private extension View {
    func workerFieldStyle() -> some View {
        padding(.horizontal, 14)
            .padding(.vertical, 13)
            .background(Color.white.opacity(0.065), in: RoundedRectangle(cornerRadius: 12))
            .overlay {
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Color.white.opacity(0.1), lineWidth: 1)
            }
    }
}

private let workerGreen = Color(red: 0.42, green: 0.91, blue: 0.59)

private func defaultWorkerId() -> String {
    let device = UIDevice.current.name
        .lowercased()
        .replacingOccurrences(of: "[^a-z0-9._-]", with: "-", options: .regularExpression)
    let suffix = UIDevice.current.identifierForVendor?.uuidString.prefix(8).lowercased() ?? "device"
    return String("ios-\(device)-\(suffix)".prefix(64))
}
