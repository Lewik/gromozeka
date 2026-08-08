import AVFAudio
import CoreBluetooth
import CoreLocation
import CoreNFC
import Foundation
import GromozekaMobileWorker
import HealthKit
import NetworkExtension
import UIKit

final class MobileWorkerSignals: NSObject {
    var onStatus: ((IosMobileWorkerStatus) -> Void)?
    var onError: ((String) -> Void)?
    var onMessage: ((String) -> Void)?

    private let runtime: IosMobileWorkerRuntime
    private let locationManager = CLLocationManager()
    private let currentLocationManager = CLLocationManager()
    private let healthStore = HKHealthStore()
    private var bluetoothManager: CBCentralManager?
    private var nfcSession: NFCTagReaderSession?
    private var sleepQuery: HKObserverQuery?
    private var currentLocationCompletion: ((Result<CLLocation, Error>) -> Void)?
    private var currentLocationRequestStarted = false
    private var connectedBleTargets: [UUID: WorkerBleDevice] = [:]
    private var retainedBlePeripherals: [UUID: CBPeripheral] = [:]
    private var started = false
    private var observers: [NSObjectProtocol] = []

    init(runtime: IosMobileWorkerRuntime) {
        self.runtime = runtime
        super.init()
        locationManager.delegate = self
        currentLocationManager.delegate = self
        currentLocationManager.desiredAccuracy = kCLLocationAccuracyBest
    }

    func start() {
        guard !started else {
            captureBattery()
            captureVehicleAudioConnection()
            synchronize()
            return
        }
        started = true
        UIDevice.current.isBatteryMonitoringEnabled = true
        observers = [
            NotificationCenter.default.addObserver(
                forName: UIDevice.batteryLevelDidChangeNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in self?.captureBattery() },
            NotificationCenter.default.addObserver(
                forName: UIDevice.batteryStateDidChangeNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in self?.captureBattery() },
            NotificationCenter.default.addObserver(
                forName: .NSProcessInfoPowerStateDidChange,
                object: nil,
                queue: .main
            ) { [weak self] _ in self?.captureBattery() },
            NotificationCenter.default.addObserver(
                forName: UIApplication.didBecomeActiveNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in self?.captureAllAvailableState() },
            NotificationCenter.default.addObserver(
                forName: UIApplication.didEnterBackgroundNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in self?.captureAllAvailableState() },
            NotificationCenter.default.addObserver(
                forName: AVAudioSession.routeChangeNotification,
                object: AVAudioSession.sharedInstance(),
                queue: .main
            ) { [weak self] _ in self?.captureVehicleAudioConnection() },
        ]
        resumeAuthorizedSignals()
        captureAllAvailableState()
    }

    func stop() {
        started = false
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        UIDevice.current.isBatteryMonitoringEnabled = false
        locationManager.stopMonitoringSignificantLocationChanges()
        currentLocationManager.stopUpdatingLocation()
        finishCurrentLocationRequest(.failure(MobileWorkerLocationError.cancelled))
        locationManager.monitoredRegions.forEach(locationManager.stopMonitoring(for:))
        bluetoothManager?.stopScan()
        retainedBlePeripherals.values.forEach { peripheral in
            bluetoothManager?.cancelPeripheralConnection(peripheral)
        }
        bluetoothManager = nil
        connectedBleTargets.removeAll()
        retainedBlePeripherals.removeAll()
        if let sleepQuery {
            healthStore.stop(sleepQuery)
        }
        sleepQuery = nil
        nfcSession?.invalidate()
        nfcSession = nil
    }

    func clearLocalConfiguration() {
        MobileWorkerSignalConfigurationStore.shared.reset()
        UserDefaults.standard.removeObject(forKey: bluetoothEnabledPreference)
        UserDefaults.standard.removeObject(forKey: blePeripheralTargetPreference)
        UserDefaults.standard.removeObject(forKey: sleepEnabledPreference)
        UserDefaults.standard.removeObject(forKey: lastSleepSessionPreference)
    }

    func requestLocationAuthorization() {
        switch locationManager.authorizationStatus {
        case .authorizedAlways:
            startLocation()
        case .authorizedWhenInUse:
            locationManager.requestAlwaysAuthorization()
            onMessage?("Choose Always in iOS Settings to enable background location events")
        case .denied, .restricted:
            onMessage?("Always-on location access is disabled in iOS Settings")
        default:
            locationManager.requestAlwaysAuthorization()
        }
    }

    func requestCurrentLocation(completion: @escaping (Result<CLLocation, Error>) -> Void) {
        guard currentLocationCompletion == nil else {
            completion(.failure(MobileWorkerLocationError.requestInProgress))
            return
        }
        currentLocationCompletion = completion
        switch currentLocationManager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            startCurrentLocationRequest()
        case .notDetermined:
            currentLocationManager.requestWhenInUseAuthorization()
        case .denied, .restricted:
            finishCurrentLocationRequest(.failure(MobileWorkerLocationError.permissionRequired))
        @unknown default:
            finishCurrentLocationRequest(.failure(MobileWorkerLocationError.permissionRequired))
        }
    }

    func enableBluetooth() {
        UserDefaults.standard.set(true, forKey: bluetoothEnabledPreference)
        startBluetooth()
    }

    func reloadConfiguration() {
        configureGeofences()
        scanForConfiguredBleDevices()
        captureWifi()
    }

    func requestSleepAuthorization() {
        guard HKHealthStore.isHealthDataAvailable(), let sleepType = sleepType else {
            onMessage?("Health data is unavailable on this device")
            return
        }
        healthStore.requestAuthorization(toShare: [], read: [sleepType]) { [weak self] granted, error in
            if let error {
                self?.onError?(error.localizedDescription)
            } else if granted {
                UserDefaults.standard.set(true, forKey: sleepEnabledPreference)
                self?.startSleepObservation()
                self?.onMessage?("Sleep access was requested; iOS does not disclose whether read access was granted")
            } else {
                UserDefaults.standard.set(false, forKey: sleepEnabledPreference)
                self?.onMessage?("Sleep access request did not complete")
            }
        }
    }

    func scanNfcTag() {
        guard NFCTagReaderSession.readingAvailable else {
            onMessage?("NFC scanning is unavailable on this device")
            return
        }
        guard let session = NFCTagReaderSession(
            pollingOption: [.iso14443, .iso15693],
            delegate: self
        ) else {
            onMessage?("NFC scanning could not start")
            return
        }
        session.alertMessage = "Hold the iPhone near an NFC tag"
        nfcSession = session
        session.begin()
    }

    private func resumeAuthorizedSignals() {
        if locationManager.authorizationStatus == .authorizedAlways {
            startLocation()
        }
        if UserDefaults.standard.bool(forKey: bluetoothEnabledPreference) {
            startBluetooth()
        }
        if HKHealthStore.isHealthDataAvailable(), UserDefaults.standard.bool(forKey: sleepEnabledPreference) {
            startSleepObservation()
        }
    }

    private func captureAllAvailableState() {
        captureBattery()
        captureVehicleAudioConnection()
        captureWifi()
        if UserDefaults.standard.bool(forKey: sleepEnabledPreference) {
            captureLatestSleep()
        }
        synchronize()
    }

    private func captureBattery() {
        let level = UIDevice.current.batteryLevel
        guard level >= 0 else { return }
        let state = UIDevice.current.batteryState
        record { completion in
            self.runtime.recordBattery(
                levelPercent: Int32((level * 100).rounded()),
                charging: state == .charging || state == .full,
                lowPowerMode: ProcessInfo.processInfo.isLowPowerModeEnabled,
                observedAtEpochMilliseconds: Date().epochMilliseconds,
                completionHandler: completion
            )
        }
    }

    private func captureVehicleAudioConnection() {
        let route = AVAudioSession.sharedInstance().currentRoute
        let connected = (route.inputs + route.outputs).contains {
            $0.portType == .carAudio
        }
        record { completion in
            self.runtime.recordVehicleAudioConnection(
                connected: connected,
                observedAtEpochMilliseconds: Date().epochMilliseconds,
                completionHandler: completion
            )
        }
    }

    private func startLocation() {
        guard CLLocationManager.significantLocationChangeMonitoringAvailable() else {
            onMessage?("Significant location changes are unavailable")
            return
        }
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.pausesLocationUpdatesAutomatically = true
        locationManager.startMonitoringSignificantLocationChanges()
        configureGeofences()
        onMessage?("Significant location changes are enabled")
    }

    private func recordLocation(_ location: CLLocation, significantChange: Bool) {
        record { completion in
            self.runtime.recordLocation(
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude,
                accuracyMeters: location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : .nan,
                altitudeMeters: location.verticalAccuracy >= 0 ? location.altitude : .nan,
                speedMetersPerSecond: location.speed >= 0 ? location.speed : .nan,
                significantChange: significantChange,
                observedAtEpochMilliseconds: location.timestamp.epochMilliseconds,
                completionHandler: completion
            )
        }
    }

    private func finishCurrentLocationRequest(_ result: Result<CLLocation, Error>) {
        let completion = currentLocationCompletion
        currentLocationCompletion = nil
        currentLocationRequestStarted = false
        completion?(result)
    }

    private func startCurrentLocationRequest() {
        guard currentLocationCompletion != nil, !currentLocationRequestStarted else { return }
        currentLocationRequestStarted = true
        currentLocationManager.requestLocation()
    }

    private func startBluetooth() {
        guard bluetoothManager == nil else { return }
        bluetoothManager = CBCentralManager(
            delegate: self,
            queue: nil,
            options: [CBCentralManagerOptionRestoreIdentifierKey: "com.gromozeka.mobile-worker.bluetooth"]
        )
    }

    private func configureGeofences() {
        guard locationManager.authorizationStatus == .authorizedAlways,
              CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self) else { return }
        locationManager.monitoredRegions
            .filter { $0.identifier.hasPrefix(geofenceIdentifierPrefix) }
            .forEach(locationManager.stopMonitoring(for:))
        let geofences = MobileWorkerSignalConfigurationStore.shared.configuration.geofences
        geofences.prefix(20).forEach { geofence in
            let region = CLCircularRegion(
                center: CLLocationCoordinate2D(
                    latitude: geofence.latitude,
                    longitude: geofence.longitude
                ),
                radius: geofence.radiusMeters,
                identifier: geofenceIdentifierPrefix + geofence.id
            )
            region.notifyOnEntry = true
            region.notifyOnExit = true
            locationManager.startMonitoring(for: region)
        }
        if geofences.count > 20 {
            onMessage?("iOS monitors the first 20 configured geofences")
        }
    }

    private func scanForConfiguredBleDevices() {
        guard let bluetoothManager, bluetoothManager.state == .poweredOn else { return }
        bluetoothManager.stopScan()
        let services = MobileWorkerSignalConfigurationStore.shared.configuration.bleDevices
            .map { CBUUID(string: $0.serviceUuid) }
        guard !services.isEmpty else { return }
        bluetoothManager.scanForPeripherals(
            withServices: services,
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
    }

    private func captureWifi() {
        guard let selected = MobileWorkerSignalConfigurationStore.shared.configuration.wifiNetworkId,
              locationManager.authorizationStatus == .authorizedAlways ||
                locationManager.authorizationStatus == .authorizedWhenInUse else { return }
        NEHotspotNetwork.fetchCurrent { [weak self] network in
            guard let self else { return }
            self.record { completion in
                self.runtime.recordWifiConnection(
                    networkId: selected,
                    connected: network?.ssid == selected,
                    observedAtEpochMilliseconds: Date().epochMilliseconds,
                    completionHandler: completion
                )
            }
        }
    }

    private func startSleepObservation() {
        guard UserDefaults.standard.bool(forKey: sleepEnabledPreference),
              sleepQuery == nil,
              let sleepType = sleepType else { return }
        let query = HKObserverQuery(sampleType: sleepType, predicate: nil) { [weak self] _, completion, error in
            if let error {
                self?.onError?(error.localizedDescription)
                completion()
                return
            }
            guard let self else {
                completion()
                return
            }
            self.captureLatestSleep(completion: completion)
        }
        sleepQuery = query
        healthStore.execute(query)
        healthStore.enableBackgroundDelivery(for: sleepType, frequency: .immediate) { [weak self] enabled, error in
            if let error {
                self?.onError?(error.localizedDescription)
            } else if enabled {
                self?.onMessage?("Sleep background delivery is enabled")
            }
        }
        captureLatestSleep()
    }

    private func captureLatestSleep(completion: (() -> Void)? = nil) {
        guard let sleepType = sleepType else {
            completion?()
            return
        }
        let start = Calendar.current.date(byAdding: .hour, value: -36, to: Date()) ?? Date.distantPast
        let predicate = HKQuery.predicateForSamples(withStart: start, end: Date(), options: [])
        let query = HKSampleQuery(
            sampleType: sleepType,
            predicate: predicate,
            limit: HKObjectQueryNoLimit,
            sortDescriptors: [NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)]
        ) { [weak self] _, samples, error in
            if let error {
                self?.onError?(error.localizedDescription)
                completion?()
                return
            }
            let asleep = (samples as? [HKCategorySample])?.filter { sample in
                sample.value != HKCategoryValueSleepAnalysis.awake.rawValue &&
                    sample.value != HKCategoryValueSleepAnalysis.inBed.rawValue
            } ?? []
            guard let session = asleep.latestContiguousSleepSession else {
                completion?()
                return
            }
            let signature = "\(session.start.epochMilliseconds):\(session.end.epochMilliseconds)"
            guard UserDefaults.standard.string(forKey: lastSleepSessionPreference) != signature else {
                completion?()
                return
            }
            guard let self else {
                completion?()
                return
            }
            self.recordSleepSession(session, signature: signature, completion: completion)
        }
        healthStore.execute(query)
    }

    private func recordSleepSession(
        _ session: (start: Date, end: Date),
        signature: String,
        completion: (() -> Void)?
    ) {
        guard started else {
            completion?()
            return
        }
        runtime.recordCompletedSleepSession(
            startedAtEpochMilliseconds: session.start.epochMilliseconds,
            endedAtEpochMilliseconds: session.end.epochMilliseconds
        ) { [weak self] error in
            guard let self else {
                completion?()
                return
            }
            if let error {
                self.onError?(error.localizedDescription)
                completion?()
                return
            }
            UserDefaults.standard.set(signature, forKey: lastSleepSessionPreference)
            completion?()
            self.synchronize()
        }
    }

    private func record(_ action: (@escaping (Error?) -> Void) -> Void) {
        guard started else { return }
        action { [weak self] error in
            if let error {
                self?.onError?(error.localizedDescription)
            } else {
                self?.synchronize()
            }
        }
    }

    private func synchronize() {
        guard started else { return }
        runtime.synchronize { [weak self] status, error in
            if let error {
                self?.onError?(error.localizedDescription)
            } else if let status {
                self?.onStatus?(status)
            }
        }
    }

    private var sleepType: HKCategoryType? {
        HKObjectType.categoryType(forIdentifier: .sleepAnalysis)
    }
}

extension MobileWorkerSignals: CLLocationManagerDelegate {
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager === currentLocationManager, currentLocationCompletion != nil {
            switch manager.authorizationStatus {
            case .authorizedAlways, .authorizedWhenInUse:
                startCurrentLocationRequest()
            case .denied, .restricted:
                finishCurrentLocationRequest(.failure(MobileWorkerLocationError.permissionRequired))
            default:
                break
            }
            return
        }
        guard manager === locationManager else { return }
        switch manager.authorizationStatus {
        case .authorizedAlways:
            startLocation()
        case .authorizedWhenInUse:
            onMessage?("Choose Always in iOS Settings to enable background location events")
        case .denied, .restricted:
            onMessage?("Always-on location access is disabled")
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard started else { return }
        guard let location = locations.last else {
            if manager === currentLocationManager {
                finishCurrentLocationRequest(.failure(MobileWorkerLocationError.noLocation))
            }
            return
        }
        if manager === currentLocationManager {
            guard currentLocationCompletion != nil else { return }
            guard abs(location.timestamp.timeIntervalSinceNow) <= currentLocationMaximumAge else {
                finishCurrentLocationRequest(.failure(MobileWorkerLocationError.staleLocation))
                return
            }
            recordLocation(location, significantChange: false)
            finishCurrentLocationRequest(.success(location))
        } else {
            recordLocation(location, significantChange: true)
        }
    }

    func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        guard started else { return }
        recordGeofence(region, entered: true)
    }

    func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        guard started else { return }
        recordGeofence(region, entered: false)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if manager === currentLocationManager {
            guard currentLocationCompletion != nil else { return }
            finishCurrentLocationRequest(.failure(error))
        } else {
            onError?(error.localizedDescription)
        }
    }

    private func recordGeofence(_ region: CLRegion, entered: Bool) {
        let regionId = region.identifier.hasPrefix(geofenceIdentifierPrefix)
            ? String(region.identifier.dropFirst(geofenceIdentifierPrefix.count))
            : region.identifier
        record { completion in
            self.runtime.recordGeofence(
                regionId: regionId,
                entered: entered,
                observedAtEpochMilliseconds: Date().epochMilliseconds,
                completionHandler: completion
            )
        }
    }
}

private enum MobileWorkerLocationError: LocalizedError {
    case cancelled
    case noLocation
    case permissionRequired
    case requestInProgress
    case staleLocation

    var errorDescription: String? {
        switch self {
        case .cancelled: "Location request was cancelled"
        case .noLocation: "iOS did not return a location"
        case .permissionRequired: "Location access is disabled in iOS Settings"
        case .requestInProgress: "A location request is already in progress"
        case .staleLocation: "iOS returned an old cached location; try again"
        }
    }
}

extension MobileWorkerSignals: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard started else { return }
        guard central.state == .poweredOn || central.state == .poweredOff else { return }
        record { completion in
            self.runtime.recordBluetoothPower(
                enabled: central.state == .poweredOn,
                observedAtEpochMilliseconds: Date().epochMilliseconds,
                completionHandler: completion
            )
        }
        if central.state == .poweredOn {
            scanForConfiguredBleDevices()
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard started else { return }
        let advertisedServices = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
        guard let target = MobileWorkerSignalConfigurationStore.shared.configuration.bleDevices.first(where: {
            advertisedServices.contains(CBUUID(string: $0.serviceUuid))
        }) else { return }
        connectedBleTargets[peripheral.identifier] = target
        retainedBlePeripherals[peripheral.identifier] = peripheral
        saveBleTarget(target.id, for: peripheral.identifier)
        recordBlePresence(target, present: true)
        if peripheral.state == .disconnected {
            central.connect(peripheral)
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard started else { return }
        retainedBlePeripherals[peripheral.identifier] = peripheral
        if let target = target(for: peripheral.identifier) {
            recordBlePresence(target, present: true)
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        guard started else { return }
        if central.state == .poweredOn, let target = target(for: peripheral.identifier) {
            recordBlePresence(target, present: false)
        }
        retainedBlePeripherals.removeValue(forKey: peripheral.identifier)
        scanForConfiguredBleDevices()
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        guard started else { return }
        let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
        peripherals.forEach { peripheral in
            guard let target = target(for: peripheral.identifier) else { return }
            connectedBleTargets[peripheral.identifier] = target
            retainedBlePeripherals[peripheral.identifier] = peripheral
            recordBlePresence(target, present: peripheral.state == .connected)
        }
    }

    private func recordBlePresence(_ target: WorkerBleDevice, present: Bool) {
        record { completion in
            self.runtime.recordBlePresence(
                deviceId: target.id,
                displayName: target.displayName,
                present: present,
                observedAtEpochMilliseconds: Date().epochMilliseconds,
                completionHandler: completion
            )
        }
    }

    private func target(for peripheralId: UUID) -> WorkerBleDevice? {
        if let current = connectedBleTargets[peripheralId] { return current }
        let values = UserDefaults.standard.dictionary(forKey: blePeripheralTargetPreference) as? [String: String]
        guard let targetId = values?[peripheralId.uuidString] else { return nil }
        return MobileWorkerSignalConfigurationStore.shared.configuration.bleDevices.first { $0.id == targetId }
    }

    private func saveBleTarget(_ targetId: String, for peripheralId: UUID) {
        var values = UserDefaults.standard.dictionary(forKey: blePeripheralTargetPreference) as? [String: String] ?? [:]
        values[peripheralId.uuidString] = targetId
        UserDefaults.standard.set(values, forKey: blePeripheralTargetPreference)
    }
}

extension MobileWorkerSignals: NFCTagReaderSessionDelegate {
    func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {}

    func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        nfcSession = nil
        if (error as NSError).code != NFCReaderError.readerSessionInvalidationErrorUserCanceled.rawValue {
            onError?(error.localizedDescription)
        }
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard started else {
            session.invalidate(errorMessage: "Mobile Worker is not enrolled")
            return
        }
        guard let tag = tags.first, let identifier = tag.hexIdentifier else {
            session.invalidate(errorMessage: "This tag has no readable identifier")
            return
        }
        runtime.recordNfcTag(
            tagId: identifier,
            observedAtEpochMilliseconds: Date().epochMilliseconds
        ) { [weak self] error in
            if let error {
                session.invalidate(errorMessage: error.localizedDescription)
                self?.onError?(error.localizedDescription)
            } else {
                session.alertMessage = "Event stored"
                session.invalidate()
                self?.synchronize()
            }
        }
    }
}

private extension NFCTag {
    var hexIdentifier: String? {
        let data: Data
        switch self {
        case .miFare(let tag): data = tag.identifier
        case .iso7816(let tag): data = tag.identifier
        case .iso15693(let tag): data = tag.identifier
        case .feliCa(let tag): data = tag.currentIDm
        @unknown default: return nil
        }
        return data.map { String(format: "%02x", $0) }.joined()
    }
}

private extension Array where Element == HKCategorySample {
    var latestContiguousSleepSession: (start: Date, end: Date)? {
        let ordered = sorted(by: { $0.endDate > $1.endDate })
        guard let latest = ordered.first else { return nil }
        var start = latest.startDate
        var end = latest.endDate
        for sample in ordered.dropFirst() {
            guard sample.endDate >= start.addingTimeInterval(-90 * 60) else { break }
            start = Swift.min(start, sample.startDate)
            end = Swift.max(end, sample.endDate)
        }
        return (start, end)
    }
}

extension Date {
    var epochMilliseconds: Int64 {
        Int64((timeIntervalSince1970 * 1_000).rounded())
    }
}

private let bluetoothEnabledPreference = "com.gromozeka.mobile-worker.bluetooth-enabled"
private let blePeripheralTargetPreference = "com.gromozeka.mobile-worker.ble-peripheral-targets"
private let sleepEnabledPreference = "com.gromozeka.mobile-worker.sleep-enabled"
private let lastSleepSessionPreference = "com.gromozeka.mobile-worker.last-sleep-session"
private let geofenceIdentifierPrefix = "gromozeka:"
private let currentLocationMaximumAge: TimeInterval = 120
