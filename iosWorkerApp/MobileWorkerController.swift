import Combine
import Foundation
import GromozekaMobileWorker
import UIKit

final class MobileWorkerRuntimeHost {
    static let shared = MobileWorkerRuntimeHost()

    let runtime: IosMobileWorkerRuntime

    private init() {
        runtime = IosMobileWorkerRuntime(
            storage: SecureMobileWorkerStorage.shared,
            deviceName: UIDevice.current.name,
            operatingSystemVersion: UIDevice.current.systemVersion,
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
        )
    }
}

@MainActor
final class MobileWorkerController: ObservableObject {
    @Published var status: IosMobileWorkerStatus?
    @Published var busy = false
    @Published var error: String?
    @Published var signalMessage: String?
    @Published var connectionCode: String?

    let signals: MobileWorkerSignals
    private let runtime = MobileWorkerRuntimeHost.shared.runtime
    private var connectionServerUrl: String?
    private var connectionToken: String?
    private var connectionExpiresAt: Date?
    private var connectionPollSeconds: Int = 2

    init() {
        signals = MobileWorkerSignals(runtime: MobileWorkerRuntimeHost.shared.runtime)
        signals.onStatus = { [weak self] status in
            Task { @MainActor in self?.status = status }
        }
        signals.onError = { [weak self] message in
            Task { @MainActor in self?.error = message }
        }
        signals.onMessage = { [weak self] message in
            Task { @MainActor in self?.signalMessage = message }
        }
        refresh()
    }

    func enroll(serverUrl: String, enrollmentToken: String, workerId: String) {
        busy = true
        error = nil
        runtime.enroll(
            serverUrl: serverUrl,
            enrollmentToken: enrollmentToken,
            workerId: workerId
        ) { [weak self] status, error in
            Task { @MainActor in
                guard let self else { return }
                self.busy = false
                if let error {
                    self.error = error.localizedDescription
                    return
                }
                self.status = status
                self.signals.start()
            }
        }
    }

    func startConnection(serverUrl: String, workerId: String) {
        busy = true
        error = nil
        Task {
            do {
                let challenge = try await runtime.startDeviceConnection(
                    serverUrl: serverUrl,
                    workerId: workerId
                )
                self.busy = false
                self.connectionServerUrl = serverUrl
                self.connectionToken = challenge.deviceToken
                self.connectionExpiresAt = Date(
                    timeIntervalSince1970: TimeInterval(challenge.expiresAtEpochMilliseconds) / 1_000
                )
                self.connectionPollSeconds = Int(challenge.pollIntervalSeconds)
                self.connectionCode = challenge.userCode
                self.pollConnection()
            } catch {
                self.busy = false
                self.error = error.localizedDescription
            }
        }
    }

    func connectWithPassword(serverUrl: String, workerId: String, username: String, password: String) {
        busy = true
        error = nil
        Task {
            do {
                let challenge = try await runtime.startDeviceConnection(
                    serverUrl: serverUrl,
                    workerId: workerId
                )
                let result = try await runtime.connectWithPassword(
                    serverUrl: serverUrl,
                    deviceToken: challenge.deviceToken,
                    username: username,
                    password: password
                )
                busy = false
                finishConnection(result)
            } catch {
                busy = false
                self.error = error.localizedDescription
            }
        }
    }

    func cancelConnection() {
        connectionCode = nil
        connectionServerUrl = nil
        connectionToken = nil
        connectionExpiresAt = nil
    }

    private func pollConnection() {
        guard let serverUrl = connectionServerUrl, let deviceToken = connectionToken else { return }
        guard connectionExpiresAt.map({ $0 > Date() }) == true else {
            error = "Connection code expired"
            cancelConnection()
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(connectionPollSeconds)) { [weak self] in
            guard let self, self.connectionToken == deviceToken else { return }
            self.runtime.consumeDeviceConnection(serverUrl: serverUrl, deviceToken: deviceToken) { [weak self] result, error in
                Task { @MainActor in
                    guard let self, self.connectionToken == deviceToken else { return }
                    if error != nil {
                        guard self.connectionExpiresAt.map({ $0 > Date() }) == true else {
                            self.error = "Connection code expired"
                            self.cancelConnection()
                            return
                        }
                        self.error = "Connection interrupted. Retrying..."
                        self.pollConnection()
                        return
                    }
                    guard let result else { return }
                    if result.status == "PENDING" {
                        self.error = nil
                        self.pollConnection()
                    } else {
                        self.finishConnection(result)
                    }
                }
            }
        }
    }

    private func finishConnection(_ result: IosMobileWorkerConnectionResult?) {
        connectionCode = nil
        connectionServerUrl = nil
        connectionToken = nil
        connectionExpiresAt = nil
        guard let result else { return }
        if result.status == "CONNECTED", let workerStatus = result.workerStatus {
            status = workerStatus
            signals.start()
            MobileWorkerBackgroundRefresh.scheduleIfEnrolled()
        } else {
            error = result.message ?? "Device connection \(result.status.lowercased())"
        }
    }

    func refresh() {
        runtime.status { [weak self] status, error in
            Task { @MainActor in
                guard let self else { return }
                if let error {
                    self.error = error.localizedDescription
                    return
                }
                self.status = status
                if status?.enrolled == true {
                    self.signals.start()
                }
            }
        }
    }

    func synchronize() {
        busy = true
        error = nil
        runtime.synchronize { [weak self] status, error in
            Task { @MainActor in
                guard let self else { return }
                self.busy = false
                if let error {
                    self.error = error.localizedDescription
                } else {
                    self.status = status
                }
            }
        }
    }

    func reset() {
        busy = true
        connectionCode = nil
        connectionServerUrl = nil
        connectionToken = nil
        connectionExpiresAt = nil
        signals.stop()
        MobileWorkerBackgroundRefresh.cancel()
        runtime.reset { [weak self] error in
            Task { @MainActor in
                guard let self else { return }
                self.busy = false
                if let error {
                    self.error = error.localizedDescription
                    self.signals.start()
                } else {
                    self.status = nil
                    self.signals.clearLocalConfiguration()
                }
            }
        }
    }
}
