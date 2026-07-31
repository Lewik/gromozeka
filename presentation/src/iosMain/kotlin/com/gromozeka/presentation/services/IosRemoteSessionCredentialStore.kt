@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.UnsafeNumber::class,
)

package com.gromozeka.presentation.services

import com.gromozeka.client.RemoteSessionCredentialStore
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecCopyErrorMessageString
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.OSStatus

class IosRemoteSessionCredentialStore : RemoteSessionCredentialStore {
    override fun load(serverKey: String): String? = retain(serverKey) { account ->
        val value = alloc<CFTypeRefVar>()
        val status = keychainOperation(
            kSecAttrAccount to account,
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        ) { SecItemCopyMatching(it, value.ptr.reinterpret()) }
        status.checkKeychainStatus(errSecItemNotFound)
        if (status == errSecItemNotFound) {
            null
        } else {
            CFBridgingRelease(value.value) as? NSData
        }?.let { NSString.create(it, NSUTF8StringEncoding)?.toKotlinString() }
    }

    override fun save(serverKey: String, encodedSession: String?) {
        if (encodedSession == null) {
            delete(serverKey)
        } else if (!add(serverKey, encodedSession)) {
            update(serverKey, encodedSession)
        }
    }

    private fun add(serverKey: String, encodedSession: String): Boolean =
        retain(serverKey, encodedSession.toNSString().dataUsingEncoding(NSUTF8StringEncoding)) { account, value ->
            val status = keychainOperation(
                kSecAttrAccount to account,
                kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                kSecValueData to value,
            ) { SecItemAdd(it, null) }
            status.checkKeychainStatus(errSecDuplicateItem)
            status != errSecDuplicateItem
        }

    private fun update(serverKey: String, encodedSession: String) =
        retain(serverKey, encodedSession.toNSString().dataUsingEncoding(NSUTF8StringEncoding)) { account, value ->
            val status = keychainOperation(kSecAttrAccount to account) { query ->
                val attributes = cfDictionaryOf(kSecValueData to value)
                val result = SecItemUpdate(query, attributes)
                CFBridgingRelease(attributes)
                result
            }
            status.checkKeychainStatus()
        }

    private fun delete(serverKey: String) = retain(serverKey) { account ->
        val status = keychainOperation(kSecAttrAccount to account, operation = ::SecItemDelete)
        status.checkKeychainStatus(errSecItemNotFound)
    }

    private inline fun MemScope.keychainOperation(
        vararg properties: Pair<CFStringRef?, CFTypeRef?>,
        operation: (CFDictionaryRef?) -> OSStatus,
    ): OSStatus {
        val service = CFBridgingRetain(SERVICE)
        try {
            val query = cfDictionaryOf(
                mapOf(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to service,
                    *properties,
                ),
            )
            val result = operation(query)
            CFBridgingRelease(query)
            return result
        } finally {
            CFBridgingRelease(service)
        }
    }

    private fun OSStatus.checkKeychainStatus(vararg allowed: OSStatus) {
        if (this == 0 || this in allowed) return
        val description = SecCopyErrorMessageString(this, null)
        val message = (CFBridgingRelease(description) as? NSString)?.toKotlinString() ?: "Unknown error"
        error("Keychain error $this: $message")
    }

    private companion object {
        const val SERVICE = "com.gromozeka.remote-session"
    }
}

private fun MemScope.cfDictionaryOf(properties: Map<CFStringRef?, CFTypeRef?>): CFDictionaryRef? {
    val keys = allocArrayOf(*properties.keys.toTypedArray())
    val values = allocArrayOf(*properties.values.toTypedArray())
    return CFDictionaryCreate(
        kCFAllocatorDefault,
        keys.reinterpret(),
        values.reinterpret(),
        properties.size.convert(),
        null,
        null,
    )
}

private fun MemScope.cfDictionaryOf(vararg properties: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef? =
    cfDictionaryOf(mapOf(*properties))

private inline fun <T> retain(value: Any?, block: MemScope.(CFTypeRef?) -> T): T = memScoped {
    val retained = CFBridgingRetain(value)
    try {
        block(retained)
    } finally {
        CFBridgingRelease(retained)
    }
}

private inline fun <T> retain(
    first: Any?,
    second: Any?,
    block: MemScope.(CFTypeRef?, CFTypeRef?) -> T,
): T = memScoped {
    val retainedFirst = CFBridgingRetain(first)
    val retainedSecond = CFBridgingRetain(second)
    try {
        block(retainedFirst, retainedSecond)
    } finally {
        CFBridgingRelease(retainedFirst)
        CFBridgingRelease(retainedSecond)
    }
}

@Suppress("CAST_NEVER_SUCCEEDS")
private fun String.toNSString(): NSString = this as NSString

@Suppress("CAST_NEVER_SUCCEEDS")
private fun NSString.toKotlinString(): String = this as String
