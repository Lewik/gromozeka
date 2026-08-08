import Foundation
import GromozekaMobileWorker
import Security

final class SecureMobileWorkerStorage: NSObject, MobileWorkerStorage {
    static let shared = SecureMobileWorkerStorage()

    private let credentialService = "com.gromozeka.mobile-worker"
    private let credentialAccount = "gateway-credential"
    private let stateUrl: URL

    override init() {
        let directory = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first!.appendingPathComponent("GromozekaMobileWorker", isDirectory: true)
        try? FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        var directoryValues = URLResourceValues()
        directoryValues.isExcludedFromBackup = true
        var protectedDirectory = directory
        try? protectedDirectory.setResourceValues(directoryValues)
        stateUrl = directory.appendingPathComponent("state.json", isDirectory: false)
        super.init()
    }

    func readState() -> String? {
        try? String(contentsOf: stateUrl, encoding: .utf8)
    }

    func writeState(value: String) {
        do {
            try Data(value.utf8).write(
                to: stateUrl,
                options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
            )
        } catch {
            NSLog("Gromozeka Worker failed to save its state: %@", error.localizedDescription)
        }
    }

    func readCredential() -> String? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: credentialService,
            kSecAttrAccount: credentialAccount,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne,
        ] as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func writeCredential(value: String) {
        let data = Data(value.utf8)
        let selector = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: credentialService,
            kSecAttrAccount: credentialAccount,
        ] as CFDictionary
        let updateStatus = SecItemUpdate(selector, [kSecValueData: data] as CFDictionary)
        if updateStatus == errSecItemNotFound {
            let addStatus = SecItemAdd([
                kSecClass: kSecClassGenericPassword,
                kSecAttrService: credentialService,
                kSecAttrAccount: credentialAccount,
                kSecValueData: data,
                kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ] as CFDictionary, nil)
            if addStatus != errSecSuccess {
                NSLog("Gromozeka Worker failed to save its credential: %d", addStatus)
            }
        } else if updateStatus != errSecSuccess {
            NSLog("Gromozeka Worker failed to update its credential: %d", updateStatus)
        }
    }

    func clearCredential() {
        SecItemDelete([
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: credentialService,
            kSecAttrAccount: credentialAccount,
        ] as CFDictionary)
    }
}
