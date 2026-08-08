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

    let signals: MobileWorkerSignals
    private let runtime = MobileWorkerRuntimeHost.shared.runtime

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
