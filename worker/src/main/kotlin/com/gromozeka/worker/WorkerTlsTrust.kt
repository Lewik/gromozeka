package com.gromozeka.worker

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal fun workerTrustManager(caCertificatePath: String): X509TrustManager {
    val path = Path.of(caCertificatePath.trim()).toAbsolutePath().normalize()
    require(Files.isRegularFile(path) && Files.isReadable(path)) {
        "Worker Server CA certificate is not a readable file: $path"
    }

    val certificates = Files.newInputStream(path).use { input ->
        CertificateFactory.getInstance("X.509")
            .generateCertificates(input)
            .map { certificate ->
                certificate as? X509Certificate
                    ?: error("Worker Server CA bundle contains a non-X.509 certificate")
            }
    }
    require(certificates.isNotEmpty()) { "Worker Server CA bundle is empty: $path" }

    val customKeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        certificates.forEachIndexed { index, certificate ->
            setCertificateEntry("gromozeka-server-ca-$index", certificate)
        }
    }
    return CompositeX509TrustManager(
        system = trustManager(null),
        custom = trustManager(customKeyStore),
    )
}

internal fun workerSslContext(caCertificatePath: String?): SSLContext? =
    caCertificatePath
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { path ->
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(workerTrustManager(path)), SecureRandom())
            }
        }

private fun trustManager(keyStore: KeyStore?): X509TrustManager =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
        init(keyStore)
        trustManagers.filterIsInstance<X509TrustManager>().singleOrNull()
            ?: error("Expected one X.509 trust manager")
    }

private class CompositeX509TrustManager(
    private val system: X509TrustManager,
    private val custom: X509TrustManager,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        verifyWithFallback(
            system = { system.checkClientTrusted(chain, authType) },
            custom = { custom.checkClientTrusted(chain, authType) },
        )
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        verifyWithFallback(
            system = { system.checkServerTrusted(chain, authType) },
            custom = { custom.checkServerTrusted(chain, authType) },
        )
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        system.acceptedIssuers + custom.acceptedIssuers

    private fun verifyWithFallback(
        system: () -> Unit,
        custom: () -> Unit,
    ) {
        try {
            system()
        } catch (systemFailure: CertificateException) {
            try {
                custom()
            } catch (customFailure: CertificateException) {
                customFailure.addSuppressed(systemFailure)
                throw customFailure
            }
        }
    }
}
