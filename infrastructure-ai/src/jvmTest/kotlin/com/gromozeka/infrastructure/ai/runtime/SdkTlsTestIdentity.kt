package com.gromozeka.infrastructure.ai.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Disposable, synthetic certificate for the loopback test server, never a production identity. */
internal class SdkTlsTestIdentity : AutoCloseable {
    private val directory = Files.createTempDirectory("grz-sdk-tls-")
    val caBundle: Path = directory.resolve("ca.pem")
    val context: SSLContext

    init {
        try {
            val store = directory.resolve("server.p12")
            keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=Synthetic loopback fixture", "-ext", "SAN=ip:127.0.0.1", "-ext", "bc=ca:true",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD, "-noprompt")
            keytool("-exportcert", "-rfc", "-alias", "server", "-keystore", store.toString(),
                "-storepass", PASSWORD, "-file", caBundle.toString())
            val keys = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(store).use { load(it, PASSWORD.toCharArray()) } }
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, PASSWORD.toCharArray()) }
            context = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun keytool(vararg args: String) {
        val executable = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
        val log = directory.resolve("keytool.log")
        val process = ProcessBuilder(listOf(executable.toString()) + args).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly(); process.waitFor()
            error("Synthetic TLS identity generation timed out")
        }
        check(process.exitValue() == 0) { "Synthetic TLS identity generation failed: ${Files.readString(log)}" }
    }

    override fun close() { check(directory.toFile().deleteRecursively()) { "Could not delete synthetic TLS test identity" } }
    private companion object { const val PASSWORD = "synthetic-test-keystore-password" }
}
