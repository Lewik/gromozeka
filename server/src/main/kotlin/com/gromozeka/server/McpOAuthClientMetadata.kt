package com.gromozeka.server

import org.apache.hc.client5.http.DnsResolver
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.util.Timeout
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.client.metadata.ClientIdMetadataDocumentResolver
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.server.authorization.OAuth2ClientRegistration
import org.springframework.security.oauth2.server.authorization.http.converter.OAuth2ClientRegistrationHttpMessageConverter
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/** Public HTTPS only, validated at the DNS resolution actually used by the socket. */
internal class McpOAuthMetadataFetcher : ClientIdMetadataDocumentResolver, AutoCloseable {
    private val client = HttpClients.custom()
        .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableAuthCaching()
        .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnTotal(4).setMaxConnPerRoute(2)
            .setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(3)).setSocketTimeout(Timeout.ofSeconds(5)).build())
            .setDnsResolver(object : DnsResolver {
                override fun resolve(host: String): Array<InetAddress> {
                    val addresses = InetAddress.getAllByName(host)
                    if (addresses.isEmpty() || addresses.any { !isPublicOAuthMetadataAddress(it) }) {
                        throw UnknownHostException("OAuth client metadata must resolve to public addresses")
                    }
                    return addresses
                }
                override fun resolveCanonicalHostname(host: String): String = host
            }).build())
        .setDefaultRequestConfig(RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofSeconds(2)).setResponseTimeout(Timeout.ofSeconds(5))
            .setAuthenticationEnabled(false).setContentCompressionEnabled(false).build())
        .build()
    private val converter = OAuth2ClientRegistrationHttpMessageConverter()

    override fun resolve(clientId: URI): ClientIdMetadataDocumentResolver.Result {
        validateOAuthClientId(clientId.toString())
        return try {
            client.execute(HttpGet(clientId).apply { setHeader("Accept", "application/json") }) { response ->
                require(response.code == 200) { "Client metadata is unavailable" }
                require(response.getFirstHeader("Content-Type")?.value?.substringBefore(';')?.trim()
                    ?.equals("application/json", ignoreCase = true) == true) { "Client metadata must be JSON" }
                val entity = requireNotNull(response.entity)
                require(entity.contentLength <= MAX_METADATA_BYTES) { "Client metadata is too large" }
                val bytes = entity.content.use { it.readNBytes(MAX_METADATA_BYTES + 1) }
                require(bytes.size <= MAX_METADATA_BYTES) { "Client metadata is too large" }
                // Validate UTF-8 rather than accepting replacement characters in identifiers.
                bytes.decodeToString(throwOnInvalidSequence = true)
                val registration = converter.read(OAuth2ClientRegistration::class.java, object : HttpInputMessage {
                    override fun getBody(): InputStream = ByteArrayInputStream(bytes)
                    override fun getHeaders(): HttpHeaders = HttpHeaders().apply { set("Content-Type", "application/json") }
                })
                val cacheControl = response.getFirstHeader("Cache-Control")?.value.orEmpty()
                val maxAge = if (cacheControl.contains("no-store", true) || cacheControl.contains("no-cache", true)) 0L
                    else Regex("(?:^|[,\\s])max-age=(\\d+)", RegexOption.IGNORE_CASE)
                        .find(cacheControl)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(0, 300) ?: 300L
                ClientIdMetadataDocumentResolver.Result(registration,
                    ClientIdMetadataDocumentResolver.ResponseAttributes(maxAge))
            }
        } catch (_: Exception) {
            throw oauthFailure("invalid_client", "Client metadata is unavailable or invalid")
        }
    }

    override fun close() { client.close() }
    private companion object { const val MAX_METADATA_BYTES = 32 * 1024 }
}

internal fun validateOAuthClientId(value: String): URI {
    val uri = try { URI(value) } catch (_: Exception) { throw oauthFailure("invalid_client", "Invalid client identifier") }
    if (value.length !in 1..McpOAuthSettings.MAX_CLIENT_ID_LENGTH || uri.scheme != "https" ||
        uri.host.isNullOrBlank() || uri.rawPath.isNullOrEmpty() ||
        uri.rawUserInfo != null || uri.rawFragment != null || (uri.port != -1 && uri.port !in 1..65535)) {
        throw oauthFailure("invalid_client", "Client identifier must be an HTTPS metadata URL")
    }
    return uri
}

internal fun isPublicOAuthMetadataAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    if (bytes.size == 4) {
        val a = bytes[0]; val b = bytes[1]; val c = bytes[2]
        return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && b in 64..127) || (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
            (a == 192 && b == 0 && c in setOf(0, 2)) ||
            (a == 198 && b in 18..19) || (a == 198 && b == 51 && c == 100) ||
            (a == 203 && b == 0 && c == 113))
    }
    if (bytes.size != 16 || bytes[0] and 0xe0 != 0x20) return false
    // Exclude documentation and IPv4 transition prefixes as well as non-global IPv6.
    return !(bytes[0] == 0x20 && ((bytes[1] == 0x01 &&
        ((bytes[2] == 0x0d && bytes[3] == 0xb8) || (bytes[2] == 0 && bytes[3] == 0))) || bytes[1] == 0x02))
}

internal fun oauthFailure(code: String, description: String): OAuth2AuthenticationException =
    OAuth2AuthenticationException(OAuth2Error(code, description, null))
