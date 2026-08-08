import Foundation

struct WorkerGeofence: Codable, Identifiable, Equatable {
    let id: String
    let latitude: Double
    let longitude: Double
    let radiusMeters: Double
}

struct WorkerBleDevice: Codable, Identifiable, Equatable {
    let id: String
    let displayName: String?
    let serviceUuid: String
}

struct MobileWorkerSignalConfiguration: Codable, Equatable {
    var geofences: [WorkerGeofence] = []
    var bleDevices: [WorkerBleDevice] = []
    var wifiNetworkId: String?
}

final class MobileWorkerSignalConfigurationStore: ObservableObject {
    static let shared = MobileWorkerSignalConfigurationStore()

    @Published private(set) var configuration: MobileWorkerSignalConfiguration

    private let key = "com.gromozeka.mobile-worker.signal-configuration"

    private init() {
        configuration = UserDefaults.standard.data(forKey: key)
            .flatMap { try? JSONDecoder().decode(MobileWorkerSignalConfiguration.self, from: $0) }
            ?? MobileWorkerSignalConfiguration()
    }

    func addGeofence(id: String, latitude: Double, longitude: Double, radiusMeters: Double) throws {
        let id = id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { throw SignalConfigurationError.invalidGeofenceName }
        guard id.utf16.count <= maximumSignalIdentifierLength else {
            throw SignalConfigurationError.geofenceNameTooLong
        }
        guard (-90...90).contains(latitude), (-180...180).contains(longitude) else {
            throw SignalConfigurationError.invalidCoordinates
        }
        guard (50...100_000).contains(radiusMeters) else {
            throw SignalConfigurationError.invalidRadius
        }
        let geofence = WorkerGeofence(
            id: id,
            latitude: latitude,
            longitude: longitude,
            radiusMeters: radiusMeters
        )
        var updated = configuration
        updated.geofences.removeAll { $0.id == id }
        updated.geofences.append(geofence)
        persist(updated)
    }

    func removeGeofence(id: String) {
        var updated = configuration
        updated.geofences.removeAll { $0.id == id }
        persist(updated)
    }

    func addBleDevice(displayName: String?, serviceUuid: String) throws {
        let value = serviceUuid.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard UUID(uuidString: value) != nil else { throw SignalConfigurationError.invalidBluetoothUuid }
        let displayName = displayName?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        guard displayName == nil || displayName!.utf16.count <= maximumSignalLabelLength else {
            throw SignalConfigurationError.bluetoothNameTooLong
        }
        let device = WorkerBleDevice(
            id: value,
            displayName: displayName,
            serviceUuid: value
        )
        var updated = configuration
        updated.bleDevices.removeAll { $0.id == device.id }
        updated.bleDevices.append(device)
        persist(updated)
    }

    func removeBleDevice(id: String) {
        var updated = configuration
        updated.bleDevices.removeAll { $0.id == id }
        persist(updated)
    }

    func setWifiNetworkId(_ value: String?) throws {
        var updated = configuration
        let networkId = value?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        guard networkId == nil || networkId!.utf16.count <= maximumSignalIdentifierLength else {
            throw SignalConfigurationError.wifiNameTooLong
        }
        updated.wifiNetworkId = networkId
        persist(updated)
    }

    func reset() {
        configuration = MobileWorkerSignalConfiguration()
        UserDefaults.standard.removeObject(forKey: key)
    }

    private func persist(_ updated: MobileWorkerSignalConfiguration) {
        configuration = updated
        if let data = try? JSONEncoder().encode(updated) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }
}

private enum SignalConfigurationError: LocalizedError {
    case invalidGeofenceName
    case geofenceNameTooLong
    case invalidCoordinates
    case invalidRadius
    case invalidBluetoothUuid
    case bluetoothNameTooLong
    case wifiNameTooLong

    var errorDescription: String? {
        switch self {
        case .invalidGeofenceName: "Geofence name cannot be empty"
        case .geofenceNameTooLong: "Geofence name must not exceed 128 characters"
        case .invalidCoordinates: "Latitude or longitude is invalid"
        case .invalidRadius: "Geofence radius must be between 50 and 100000 meters"
        case .invalidBluetoothUuid: "Enter a full BLE service UUID"
        case .bluetoothNameTooLong: "Bluetooth display name must not exceed 255 characters"
        case .wifiNameTooLong: "Wi-Fi network name must not exceed 128 characters"
        }
    }
}

private let maximumSignalIdentifierLength = 128
private let maximumSignalLabelLength = 255

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
