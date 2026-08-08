import SwiftUI

struct MobileWorkerSignalSettingsView: View {
    let signals: MobileWorkerSignals

    @ObservedObject private var store = MobileWorkerSignalConfigurationStore.shared
    @State private var geofenceName = ""
    @State private var latitude = ""
    @State private var longitude = ""
    @State private var radius = "250"
    @State private var bleName = ""
    @State private var bleServiceUuid = ""
    @State private var wifiNetworkId = ""
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("CONFIGURED SIGNALS")
                .font(.system(.caption, design: .monospaced, weight: .bold))
                .foregroundStyle(Color(red: 0.42, green: 0.91, blue: 0.59))
            Text("Only the geofences, BLE services and Wi-Fi network listed here are monitored.")
                .font(.footnote)
                .foregroundStyle(.secondary)

            signalTitle("Geofences")
            HStack {
                workerTextField("Name", text: $geofenceName)
                Button("Use current") {
                    guard let coordinate = signals.latestLocationCoordinate else {
                        error = "No recent location is available yet"
                        return
                    }
                    latitude = String(coordinate.latitude)
                    longitude = String(coordinate.longitude)
                }
                .buttonStyle(.bordered)
            }
            HStack {
                workerTextField("Latitude", text: $latitude)
                workerTextField("Longitude", text: $longitude)
            }
            HStack {
                workerTextField("Radius, m", text: $radius)
                Button("Add") { addGeofence() }
                    .buttonStyle(.borderedProminent)
            }
            ForEach(store.configuration.geofences) { geofence in
                configuredRow(
                    title: geofence.id,
                    detail: "\(geofence.latitude), \(geofence.longitude) / \(Int(geofence.radiusMeters)) m"
                ) {
                    store.removeGeofence(id: geofence.id)
                    signals.reloadConfiguration()
                }
            }

            signalTitle("BLE devices")
            workerTextField("Display name (optional)", text: $bleName)
            workerTextField("Full service UUID", text: $bleServiceUuid)
            Button("Add BLE service") { addBleDevice() }
                .buttonStyle(.borderedProminent)
            ForEach(store.configuration.bleDevices) { device in
                configuredRow(title: device.displayName ?? device.id, detail: device.serviceUuid) {
                    store.removeBleDevice(id: device.id)
                    signals.reloadConfiguration()
                }
            }

            signalTitle("Wi-Fi")
            HStack {
                workerTextField("Selected network name", text: $wifiNetworkId)
                Button("Save") {
                    do {
                        try store.setWifiNetworkId(wifiNetworkId)
                        error = nil
                        signals.reloadConfiguration()
                    } catch {
                        self.error = error.localizedDescription
                    }
                }
                .buttonStyle(.borderedProminent)
            }
            if let selected = store.configuration.wifiNetworkId {
                configuredRow(title: selected, detail: "Selected network") {
                    wifiNetworkId = ""
                    try? store.setWifiNetworkId(nil)
                    signals.reloadConfiguration()
                }
            }

            if let error {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(Color(red: 1, green: 0.42, blue: 0.35))
            }
        }
        .onAppear {
            wifiNetworkId = store.configuration.wifiNetworkId ?? ""
        }
    }

    private func addGeofence() {
        do {
            guard let latitude = Double(latitude),
                  let longitude = Double(longitude),
                  let radius = Double(radius) else {
                throw LocalConfigurationError.invalidNumber
            }
            try store.addGeofence(
                id: geofenceName,
                latitude: latitude,
                longitude: longitude,
                radiusMeters: radius
            )
            geofenceName = ""
            error = nil
            signals.reloadConfiguration()
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func addBleDevice() {
        do {
            try store.addBleDevice(displayName: bleName, serviceUuid: bleServiceUuid)
            bleName = ""
            bleServiceUuid = ""
            error = nil
            signals.enableBluetooth()
            signals.reloadConfiguration()
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func signalTitle(_ value: String) -> some View {
        Text(value)
            .font(.headline)
            .padding(.top, 4)
    }

    private func workerTextField(_ title: String, text: Binding<String>) -> some View {
        TextField(title, text: text)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .padding(.horizontal, 12)
            .padding(.vertical, 11)
            .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 11))
    }

    private func configuredRow(
        title: String,
        detail: String,
        onRemove: @escaping () -> Void
    ) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).fontWeight(.semibold)
                Text(detail)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Button("Remove", role: .destructive, action: onRemove)
                .font(.caption)
        }
    }
}

private enum LocalConfigurationError: LocalizedError {
    case invalidNumber

    var errorDescription: String? {
        "Enter valid numeric coordinates and radius"
    }
}
