package com.gromozeka.client

import com.gromozeka.remote.protocol.ClientInstanceId
import com.gromozeka.remote.protocol.RemoteProtocolEncoding
import com.gromozeka.domain.model.TranslationSnapshot
import com.gromozeka.domain.model.User
import com.gromozeka.shared.localization.BundledTranslations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Serializable
data class RemoteClientSettings(
    val remoteUrl: String? = null,
    val protocolEncoding: RemoteProtocolEncoding = RemoteProtocolEncoding.CBOR,
    val clientInstanceId: ClientInstanceId? = null,
    val bootstrapLocale: String? = null,
    val translationCache: Map<String, TranslationSnapshot> = emptyMap(),
)

interface RemoteClientSettingsStore {
    fun load(): RemoteClientSettings?
    fun save(settings: RemoteClientSettings)
}

class InMemoryRemoteClientSettingsStore : RemoteClientSettingsStore {
    private var settings: RemoteClientSettings? = null

    override fun load(): RemoteClientSettings? = settings

    override fun save(settings: RemoteClientSettings) {
        this.settings = settings
    }
}

fun RemoteClientSettingsStore.resolveRemoteUrl(
    explicitUrl: String? = null,
    fallbackUrl: String? = null,
): String? =
    sequenceOf(explicitUrl, load()?.remoteUrl, fallbackUrl)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull()
        ?.let(::normalizeRemoteUrl)

fun RemoteClientSettingsStore.saveRemoteUrl(remoteUrl: String): String {
    val normalized = normalizeRemoteUrl(remoteUrl)
    save((load() ?: RemoteClientSettings()).copy(remoteUrl = normalized))
    return normalized
}

class RemoteServerAddressException(val messageKey: String) : IllegalArgumentException(messageKey)

private fun addressRequire(condition: Boolean, messageKey: String) {
    if (!condition) throw RemoteServerAddressException(messageKey)
}

fun normalizeRemoteUrl(value: String): String {
    val trimmed = value.trim()
    addressRequire(trimmed.isNotEmpty(), "client.serverAddress.empty")
    addressRequire(trimmed.none(Char::isWhitespace), "client.serverAddress.whitespace")

    val withScheme = if ("://" in trimmed) {
        trimmed
    } else {
        val scheme = if (trimmed.substringBefore('/').serverHost().isLocalServerHost()) "http" else "https"
        "$scheme://$trimmed"
    }
    val scheme = withScheme.substringBefore("://").lowercase()
    val remainder = withScheme.substringAfter("://")
    addressRequire(scheme in setOf("http", "https", "ws", "wss"), "client.serverAddress.scheme")
    addressRequire(remainder.isNotEmpty(), "client.serverAddress.host")
    addressRequire('@' !in remainder.substringBefore('/'), "client.serverAddress.credentials")
    addressRequire('?' !in remainder && '#' !in remainder, "client.serverAddress.query")

    val authority = remainder.substringBefore('/')
    addressRequire(authority.isNotEmpty(), "client.serverAddress.host")
    authority.serverHost()
    val path = remainder.substringAfter('/', missingDelimiterValue = "")
        .trimEnd('/')
    addressRequire(path.isEmpty() || path == "ws", "client.serverAddress.path")

    val websocketScheme = when (scheme) {
        "http", "ws" -> "ws"
        else -> "wss"
    }
    return "$websocketScheme://$authority/ws"
}

private fun String.serverHost(): String {
    addressRequire('@' !in this, "client.serverAddress.credentials")
    if (startsWith('[')) {
        val closingBracket = indexOf(']')
        addressRequire(closingBracket > 1, "client.serverAddress.invalidIpv6")
        val host = substring(1, closingBracket)
        validateServerPort(substring(closingBracket + 1))
        return host
    }

    addressRequire(count { it == ':' } <= 1, "client.serverAddress.ipv6Brackets")
    val portSeparator = indexOf(':')
    val host = if (portSeparator >= 0) substring(0, portSeparator) else this
    addressRequire(host.isNotEmpty(), "client.serverAddress.host")
    validateServerPort(if (portSeparator >= 0) substring(portSeparator) else "")
    return host
}

private fun validateServerPort(suffix: String) {
    if (suffix.isEmpty()) return
    addressRequire(suffix.startsWith(':'), "client.serverAddress.invalidSuffix")
    val port = suffix.drop(1).toIntOrNull()
    addressRequire(port in 1..65535, "client.serverAddress.invalidPort")
}

private fun String.isLocalServerHost(): Boolean =
    equals("localhost", ignoreCase = true) ||
        equals("::1", ignoreCase = true) ||
        split('.').let { parts ->
            parts.size == 4 &&
                parts.first() == "127" &&
                parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
        }

class RemoteClientSettingsService internal constructor(
    private val client: GromozekaWsClient,
    private val store: RemoteClientSettingsStore,
    initialSettings: RemoteClientSettings,
) {
    private val mutations = Mutex()
    private val _settingsFlow = MutableStateFlow(initialSettings)
    val settingsFlow: StateFlow<RemoteClientSettings> = _settingsFlow.asStateFlow()

    init {
        client.setEncoding(initialSettings.protocolEncoding)
    }

    suspend fun saveSettings(settings: RemoteClientSettings) = mutations.withLock {
        val updated = settings.copy(
            bootstrapLocale = _settingsFlow.value.bootstrapLocale,
            translationCache = _settingsFlow.value.translationCache,
        )
        store.save(updated)
        _settingsFlow.value = updated
        client.setEncoding(updated.protocolEncoding)
    }

    suspend fun updateProtocolEncoding(encoding: RemoteProtocolEncoding) {
        saveSettings(_settingsFlow.value.copy(protocolEncoding = encoding))
    }

    fun cachedTranslation(serverUrl: String, userId: User.Id): TranslationSnapshot? =
        _settingsFlow.value.translationCache[translationCacheKey(serverUrl, userId)]

    suspend fun cacheTranslation(serverUrl: String, userId: User.Id, snapshot: TranslationSnapshot) = mutations.withLock {
        val current = _settingsFlow.value
        val updated = current.copy(
            bootstrapLocale = BundledTranslations.matchLocale(snapshot.selectedPackage.locale),
            translationCache = current.translationCache + (translationCacheKey(serverUrl, userId) to snapshot),
        )
        store.save(updated)
        _settingsFlow.value = updated
    }

    private fun translationCacheKey(serverUrl: String, userId: User.Id): String =
        "${normalizeRemoteUrl(serverUrl)}\n${userId.value}"
}
