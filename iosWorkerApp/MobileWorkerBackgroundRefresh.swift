import BackgroundTasks
import GromozekaMobileWorker
import UIKit

final class MobileWorkerAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: mobileWorkerRefreshTaskIdentifier,
            using: nil
        ) { task in
            guard let refreshTask = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            MobileWorkerBackgroundRefresh.run(refreshTask)
        }
        return true
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        MobileWorkerBackgroundRefresh.scheduleIfEnrolled()
    }
}

enum MobileWorkerBackgroundRefresh {
    static func scheduleIfEnrolled() {
        MobileWorkerRuntimeHost.shared.runtime.status { status, error in
            if error == nil, status?.enrolled == true {
                schedule()
            }
        }
    }

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: mobileWorkerRefreshTaskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    static func cancel() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: mobileWorkerRefreshTaskIdentifier)
    }

    static func run(_ task: BGAppRefreshTask) {
        let completion = MobileWorkerBackgroundTaskCompletion(task)
        task.expirationHandler = { completion.finish(success: false) }
        let runtime = MobileWorkerRuntimeHost.shared.runtime
        runtime.status { status, error in
            guard error == nil, status?.enrolled == true else {
                completion.finish(success: error == nil)
                return
            }
            schedule()
            UIDevice.current.isBatteryMonitoringEnabled = true
            let level = UIDevice.current.batteryLevel
            guard level >= 0 else {
                synchronize(runtime, completion: completion)
                return
            }
            let state = UIDevice.current.batteryState
            runtime.recordBattery(
                levelPercent: Int32((level * 100).rounded()),
                charging: state == .charging || state == .full,
                lowPowerMode: ProcessInfo.processInfo.isLowPowerModeEnabled,
                observedAtEpochMilliseconds: Date().epochMilliseconds
            ) { error in
                guard error == nil else {
                    completion.finish(success: false)
                    return
                }
                synchronize(runtime, completion: completion)
            }
        }
    }

    private static func synchronize(
        _ runtime: IosMobileWorkerRuntime,
        completion: MobileWorkerBackgroundTaskCompletion
    ) {
        runtime.synchronize { _, error in
            completion.finish(success: error == nil)
        }
    }
}

private final class MobileWorkerBackgroundTaskCompletion: @unchecked Sendable {
    private let lock = NSLock()
    private let task: BGTask
    private var finished = false

    init(_ task: BGTask) {
        self.task = task
    }

    func finish(success: Bool) {
        lock.lock()
        guard !finished else {
            lock.unlock()
            return
        }
        finished = true
        lock.unlock()
        task.setTaskCompleted(success: success)
    }
}

private let mobileWorkerRefreshTaskIdentifier = "com.lewik.gromozeka.mobile.worker.refresh"
