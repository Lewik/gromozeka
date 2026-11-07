package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiHttpSettings
import com.gromozeka.domain.service.SettingsProvider
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import klog.KLoggers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object SdkHttpSupport {
    fun settings(connection: AiConnection): AiHttpSettings =
        (connection as? AiConnection.ConfigurableHttpAiConnection)?.httpSettings
            ?: error("Connection does not support configurable SDK HTTP settings")

    fun baseUrl(connection: AiConnection, nativeDefault: String): String {
        val value = (connection as AiConnection.HttpAiConnection).baseUrl ?: nativeDefault
        val uri = uri(value, "AI base URL")
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "AI base URL must be an absolute HTTP(S) inference prefix without credentials, query or fragment"
        }
        return value
    }

    fun token(connection: AiConnection, settingsProvider: SettingsProvider, nativeEnvironmentVariable: String): String {
        val http = connection as AiConnection.ConfigurableHttpAiConnection
        val source = http.apiKey ?: run {
            require(http.baseUrl == null && http.httpSettings.authentication == AiHttpSettings.Authentication.PROVIDER_DEFAULT) {
                "An explicit token source is required for the configured AI endpoint"
            }
            SecretRef.EnvironmentVariable(nativeEnvironmentVariable)
        }
        val token = requireNotNull(settingsProvider.resolveSecret(source)) { "Configured AI token source is unavailable on the execution target" }
        require(token.isNotEmpty() && token.all { it in '!'..'~' }) { "AI token must be nonempty ASCII without whitespace" }
        unverifiedJwtExpiry(token)?.let { expiresAt ->
            if (!expiresAt.isAfter(Instant.now().plusSeconds(300))) {
                KLoggers.logger(this).warn {
                    "AI token for connection ${connection.id.value} has an unverified JWT exp claim that is past or within five minutes; this is not signature or authentication validation"
                }
            }
        }
        return token
    }

    fun proxy(settings: AiHttpSettings, baseUrl: String): Proxy? {
        val endpoint = uri(baseUrl, "AI base URL")
        val configuredProxy = settings.proxyUrl?.let { value ->
            val proxy = uri(value, "Proxy URL")
            require(proxy.scheme.equals("http", ignoreCase = true) && !proxy.host.isNullOrBlank() && proxy.rawUserInfo == null &&
                proxy.rawQuery == null && proxy.rawFragment == null && proxy.path in listOf("", "/")) {
                "Proxy URL must be an HTTP proxy endpoint without credentials, query or fragment"
            }
            require(proxy.port == -1 || proxy.port in 1..65535) { "Invalid proxy port" }
            Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxy.host, if (proxy.port == -1) 80 else proxy.port))
        }
        val port = if (endpoint.port != -1) endpoint.port else if (endpoint.scheme.equals("https", ignoreCase = true)) 443 else 80
        return if (settings.bypassesProxy(endpoint.host, port)) Proxy.NO_PROXY else configuredProxy
    }

    fun tls(settings: AiHttpSettings): Tls? {
        val configuredPath = settings.caBundlePath ?: return null
        val path = try { Path.of(configuredPath) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid CA bundle path on the execution target")
        }
        require(path.isAbsolute) { "CA bundle path must be absolute on the execution target" }
        val certificates = try {
            Files.newInputStream(path).use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
        } catch (_: java.io.IOException) {
            throw IllegalStateException("Cannot read the configured CA bundle on the execution target")
        } catch (_: CertificateException) {
            throw IllegalArgumentException("CA bundle must contain valid X.509 certificates")
        }
        require(certificates.isNotEmpty()) { "CA bundle contains no certificates" }
        val additional = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certificates.forEachIndexed { index, certificate -> setCertificateEntry("additional-$index", certificate) }
        }
        fun manager(store: KeyStore?): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(store)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        }
        val combined = AdditionalCaTrustManager(manager(null), manager(additional))
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(combined), null) }
        return Tls(context.socketFactory, combined)
    }

    /** Header names are case-insensitive, while the SDK's default User-Agent check uses canonical spelling. */
    fun headers(settings: AiHttpSettings): Map<String, String> = settings.extraHeaders.mapKeys { (name, _) ->
        if (name.equals("user-agent", ignoreCase = true)) "User-Agent" else name
    }

    // Only JWT syntax is inspected. Opaque tokens, malformed values and absent exp are not authentication failures.
    internal fun unverifiedJwtExpiry(token: String): Instant? {
        if (token.length > 65_536) return null
        val parts = token.split('.')
        if (parts.size != 3) return null
        return try {
            fun objectPart(value: String) = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)) as? JsonObject
            val header = objectPart(parts[0]) ?: return null
            val algorithm = header["alg"] as? JsonPrimitive ?: return null
            if (!algorithm.isString || algorithm.content.isBlank()) return null
            val exp = objectPart(parts[1])?.get("exp") as? JsonPrimitive ?: return null
            if (exp.isString) return null
            exp.longOrNull?.let(Instant::ofEpochSecond)
        } catch (_: IllegalArgumentException) { null }
        catch (_: java.time.DateTimeException) { null }
    }

    private fun uri(value: String, description: String): URI = try { URI(value) } catch (_: java.net.URISyntaxException) {
        throw IllegalArgumentException("Invalid $description")
    }

    data class Tls(val socketFactory: SSLSocketFactory, val trustManager: X509TrustManager)

    private class AdditionalCaTrustManager(private val standard: X509TrustManager, private val additional: X509TrustManager) : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = standard.acceptedIssuers + additional.acceptedIssuers
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = check(
            { standard.checkClientTrusted(chain, authType) }, { additional.checkClientTrusted(chain, authType) })
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = check(
            { standard.checkServerTrusted(chain, authType) }, { additional.checkServerTrusted(chain, authType) })

        private fun check(standardCheck: () -> Unit, additionalCheck: () -> Unit) {
            try { standardCheck() } catch (standardFailure: CertificateException) {
                try { additionalCheck() } catch (additionalFailure: CertificateException) {
                    additionalFailure.addSuppressed(standardFailure)
                    throw additionalFailure
                }
            }
        }
    }
}
