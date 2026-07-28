package com.gromozeka.client

import com.gromozeka.remote.protocol.ClientInstanceId
import com.gromozeka.remote.protocol.RemoteProtocolEncoding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class RemoteClientSettings(
    val remoteUrl: String? = null,
    val protocolEncoding: RemoteProtocolEncoding = RemoteProtocolEncoding.CBOR,
    val clientInstanceId: ClientInstanceId? = null,
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

fun normalizeRemoteUrl(value: String): String {
    val trimmed = value.trim()
    require(trimmed.isNotEmpty()) { "Server address must not be empty" }
    require(trimmed.none(Char::isWhitespace)) { "Server address must not contain whitespace" }

    val withScheme = if ("://" in trimmed) {
        trimmed
    } else {
        val scheme = if (trimmed.substringBefore('/').serverHost().isLocalServerHost()) "http" else "https"
        "$scheme://$trimmed"
    }
    val scheme = withScheme.substringBefore("://").lowercase()
    val remainder = withScheme.substringAfter("://")
    require(scheme in setOf("http", "https", "ws", "wss")) {
        "Server address must use http://, https://, ws://, or wss://"
    }
    require(remainder.isNotEmpty()) { "Server address must include a host" }
    require('@' !in remainder.substringBefore('/')) { "Server address must not contain credentials" }
    require('?' !in remainder && '#' !in remainder) {
        "Server address must not contain a query or fragment"
    }

    val authority = remainder.substringBefore('/')
    require(authority.isNotEmpty()) { "Server address must include a host" }
    authority.serverHost()
    val path = remainder.substringAfter('/', missingDelimiterValue = "")
        .trimEnd('/')
    require(path.isEmpty() || path == "ws") {
        "Server address path must be empty or /ws"
    }

    val websocketScheme = when (scheme) {
        "http", "ws" -> "ws"
        else -> "wss"
    }
    return "$websocketScheme://$authority/ws"
}

private fun String.serverHost(): String {
    require('@' !in this) { "Server address must not contain credentials" }
    if (startsWith('[')) {
        val closingBracket = indexOf(']')
        require(closingBracket > 1) { "Server address contains an invalid IPv6 host" }
        val host = substring(1, closingBracket)
        validateServerPort(substring(closingBracket + 1))
        return host
    }

    require(count { it == ':' } <= 1) {
        "IPv6 server addresses must use square brackets"
    }
    val portSeparator = indexOf(':')
    val host = if (portSeparator >= 0) substring(0, portSeparator) else this
    require(host.isNotEmpty()) { "Server address must include a host" }
    validateServerPort(if (portSeparator >= 0) substring(portSeparator) else "")
    return host
}

private fun validateServerPort(suffix: String) {
    if (suffix.isEmpty()) return
    require(suffix.startsWith(':')) { "Server address contains invalid text after the host" }
    val port = suffix.drop(1).toIntOrNull()
    require(port in 1..65535) { "Server address contains an invalid port" }
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
    private val _settingsFlow = MutableStateFlow(initialSettings)
    val settingsFlow: StateFlow<RemoteClientSettings> = _settingsFlow.asStateFlow()

    init {
        client.setEncoding(initialSettings.protocolEncoding)
    }

    fun saveSettings(settings: RemoteClientSettings) {
        store.save(settings)
        _settingsFlow.value = settings
        client.setEncoding(settings.protocolEncoding)
    }

    fun updateProtocolEncoding(encoding: RemoteProtocolEncoding) {
        saveSettings(_settingsFlow.value.copy(protocolEncoding = encoding))
    }
}
