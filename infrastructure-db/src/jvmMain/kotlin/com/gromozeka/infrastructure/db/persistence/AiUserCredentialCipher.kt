package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.service.SettingsProvider
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Service
internal class AiUserCredentialCipher(
    private val settingsProvider: SettingsProvider,
) {
    private val secureRandom = SecureRandom()
    private val keyLock = Any()

    @Volatile
    private var cachedKey: SecretKeySpec? = null

    fun encrypt(secret: String, associatedData: String): EncryptedAiUserCredential {
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(secret.toByteArray(StandardCharsets.UTF_8))
        return EncryptedAiUserCredential(
            ciphertext = Base64.getEncoder().encodeToString(ciphertext),
            nonce = Base64.getEncoder().encodeToString(nonce),
            version = VERSION,
        )
    }

    fun decrypt(
        encrypted: EncryptedAiUserCredential,
        associatedData: String,
    ): String {
        require(encrypted.version == VERSION) {
            "Unsupported AI user credential encryption version ${encrypted.version}"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(encrypted.nonce)),
        )
        cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        return cipher.doFinal(Base64.getDecoder().decode(encrypted.ciphertext))
            .toString(StandardCharsets.UTF_8)
    }

    private fun key(): SecretKeySpec = cachedKey ?: synchronized(keyLock) {
        cachedKey ?: SecretKeySpec(loadOrCreateKey(), "AES").also { cachedKey = it }
    }

    private fun loadOrCreateKey(): ByteArray {
        val path = Path.of(settingsProvider.homeDirectory, "secrets", "ai-user-credentials.key")
        Files.createDirectories(path.parent)
        restrictDirectoryToOwner(path.parent)
        val bytes = if (Files.exists(path)) {
            Files.readAllBytes(path)
        } else {
            createKey(path)
        }
        require(bytes.size == KEY_BYTES) { "AI user credential key must contain exactly $KEY_BYTES bytes" }
        restrictToOwner(path)
        return bytes
    }

    private fun createKey(path: Path): ByteArray {
        val candidate = ByteArray(KEY_BYTES).also(secureRandom::nextBytes)
        return try {
            Files.write(path, candidate, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            candidate
        } catch (_: FileAlreadyExistsException) {
            Files.readAllBytes(path)
        }
    }

    private fun restrictToOwner(path: Path) {
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) == null) return
        Files.setPosixFilePermissions(
            path,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
    }

    private fun restrictDirectoryToOwner(path: Path) {
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) == null) return
        Files.setPosixFilePermissions(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    }

    companion object {
        private const val VERSION = 1
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal data class EncryptedAiUserCredential(
    val ciphertext: String,
    val nonce: String,
    val version: Int,
)
