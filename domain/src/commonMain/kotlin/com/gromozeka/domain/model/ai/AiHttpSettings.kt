package com.gromozeka.domain.model.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Executor-local HTTP settings for the existing SDK APIs; never a protocol selector. */
@Serializable
data class AiHttpSettings(
    val authentication: Authentication = Authentication.PROVIDER_DEFAULT,
    val caBundlePath: String? = null,
    val proxyUrl: String? = null,
    val noProxy: List<String> = emptyList(),
    val extraHeaders: Map<String, String> = emptyMap(),
    val maxConcurrentCalls: Int = 4,
    val maxAttempts: Int = 3,
) {
    init {
        require(maxConcurrentCalls in 1..64) { "AI HTTP concurrency must be between 1 and 64" }
        require(maxAttempts in 1..5) { "AI HTTP max attempts must be between 1 and 5" }
        require(caBundlePath == null || caBundlePath.isNotBlank()) { "CA bundle path must not be blank" }
        proxyUrl?.let {
            val match = Regex("http://(\\[[0-9A-Fa-f:]+\\]|[A-Za-z0-9.-]+)(?::([0-9]+))?/?", RegexOption.IGNORE_CASE).matchEntire(it)
            require(match != null) { "Proxy URL must be an HTTP endpoint without credentials, query or fragment" }
            val port = match.groupValues[2]
            require(port.isEmpty() || port.toIntOrNull()?.let { number -> number in 1..65535 } == true) { "Invalid proxy port" }
        }
        require(noProxy.all { it.isNotBlank() && it.none(Char::isWhitespace) }) { "noProxy entries must be nonblank host rules" }
        noProxy.forEach(::AiNoProxyRule)
        require(extraHeaders.keys.map { it.lowercase() }.distinct().size == extraHeaders.size) {
            "Extra HTTP headers must be unique ignoring case"
        }
        for ((name, value) in extraHeaders) {
            require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "Invalid extra HTTP header name" }
            require(name.lowercase() !in RESERVED_HEADERS) { "Authentication and transport headers cannot be set through extraHeaders" }
            require(value.all { it == '\t' || it in ' '..'~' }) {
                "Extra HTTP header values must be ASCII without line breaks; escape Unicode in JSON metadata"
            }
            if (name.equals("x-tfy-metadata", ignoreCase = true)) {
                val metadata = try { Json.parseToJsonElement(value) } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("X-TFY-METADATA must be a JSON object of strings")
                }
                require(metadata is JsonObject && metadata.values.all {
                    it is JsonPrimitive && it.isString && it.content.count { char -> char !in '\uDC00'..'\uDFFF' } <= 128
                }) { "X-TFY-METADATA values must be strings of at most 128 characters" }
            }
        }
    }

    fun bypassesProxy(host: String, port: Int): Boolean = noProxy.any { AiNoProxyRule(it).matches(host, port) }

    @Serializable
    enum class Authentication {
        PROVIDER_DEFAULT,
        BEARER,
    }

    companion object {
        fun validateBaseUrl(value: String?) {
            require(value == null || Regex("https?://[^/?#@\\s]+(?:/[^?#\\s]*)?", RegexOption.IGNORE_CASE).matches(value)) {
                "AI base URL must be an absolute HTTP(S) inference prefix without credentials, query or fragment"
            }
        }

        private val RESERVED_HEADERS = setOf("authorization", "proxy-authorization", "x-api-key", "api-key", "cookie", "set-cookie",
            "host", "content-length", "transfer-encoding", "connection", "upgrade")
    }
}

private class AiNoProxyRule(value: String) {
    private val all = value == "*"
    private val host: String
    private val port: Int?
    private val suffix: Boolean

    init {
        val bracketed = value.startsWith('[')
        val colon = if (bracketed) value.indexOf(']').let { if (it >= 0 && value.getOrNull(it + 1) == ':') it + 1 else -1 }
            else if (value.count { it == ':' } == 1) value.lastIndexOf(':') else -1
        val hostPart = if (colon >= 0) value.substring(0, colon) else value
        port = if (colon >= 0) value.substring(colon + 1).toIntOrNull().also {
            require(it != null && it in 1..65535) { "Invalid port in noProxy rule" }
        } else null
        suffix = hostPart.startsWith('.')
        host = hostPart.removePrefix(".").removePrefix("[").removeSuffix("]").lowercase().removeSuffix(".")
        require(all || (host.isNotBlank() && host.all { it.isLetterOrDigit() || it in ".-:" } &&
            !hostPart.contains('*') && !hostPart.contains('/'))) {
            "noProxy supports hosts, domain suffixes, IP literals, optional ports and '*'; CIDR and wildcard patterns are not supported"
        }
        require(!bracketed || hostPart.endsWith(']')) { "Invalid bracketed IP literal in noProxy" }
    }

    fun matches(candidate: String, candidatePort: Int): Boolean {
        if (all) return true
        val normalized = candidate.removePrefix("[").removeSuffix("]").lowercase().removeSuffix(".")
        return (port == null || port == candidatePort) &&
            (normalized == host || (suffix && normalized.endsWith(".$host")))
    }
}
