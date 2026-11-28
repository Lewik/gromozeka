package com.gromozeka.infrastructure.ai.openai.siwc

import com.gromozeka.domain.service.SettingsProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One execution host owns one rotating SIWC grant. All refreshes run under the same process/file lock. */
@Component
internal class OpenAiSiwcCredentialStore(
    settingsProvider: SettingsProvider,
) {
    private val directory = Path.of(settingsProvider.homeDirectory, "openai-siwc")
    private val lockPath = directory.resolve(".credentials.lock")
    private val statePath = directory.resolve("credentials.json")
    private val keyPath = directory.resolve("credentials.key")
    private val hostPath = directory.resolve("host.json")
    private val random = SecureRandom()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val processLock = ReentrantLock()

    fun hostId(): String = locked {
        val existing = readPublic<HostIdentity>(hostPath)
        if (existing != null) {
            require(existing.version == 1 && existing.id.startsWith("urn:uuid:")) { "Invalid SIWC host identity" }
            existing.id
        } else {
            val created = HostIdentity(id = "urn:uuid:${UUID.randomUUID()}")
            writePublic(hostPath, json.encodeToString(HostIdentity.serializer(), created))
            created.id
        }
    }

    fun read(): OpenAiSiwcStoredState? = locked { readState() }

    fun <T> update(operation: (OpenAiSiwcStoredState?) -> Pair<OpenAiSiwcStoredState?, T>): T = locked {
        val (next, result) = operation(readState())
        if (next == null) Files.deleteIfExists(statePath) else writeState(next)
        result
    }

    /** The operation may perform a token refresh; the lock deliberately remains held until its rotation is durable. */
    fun <T> lockedState(deadlineNanos: Long, operation: LockedState.() -> T): T = locked(deadlineNanos) {
        LockedState(readState()).operation()
    }

    inner class LockedState internal constructor(var state: OpenAiSiwcStoredState?) {
        fun save(next: OpenAiSiwcStoredState) {
            writeState(next)
            state = next
        }
    }

    private fun <T> locked(operation: () -> T): T = locked(null, operation)

    private fun <T> locked(deadlineNanos: Long?, operation: () -> T): T {
        acquireProcessLock(deadlineNanos)
        return try {
            deadlineNanos?.let(::remainingNanos)
            prepareDirectory()
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                restrictFile(lockPath)
                acquireFileLock(channel, deadlineNanos).use {
                    deadlineNanos?.let(::remainingNanos)
                    operation()
                }
            }
        } finally {
            processLock.unlock()
        }
    }

    private fun acquireProcessLock(deadlineNanos: Long?) {
        if (deadlineNanos == null) {
            processLock.lock()
            return
        }
        val acquired = try {
            processLock.tryLock(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw lockInterrupted(error)
        }
        if (!acquired) throw siwcDeadlineExceeded()
    }

    private fun acquireFileLock(channel: FileChannel, deadlineNanos: Long?): FileLock {
        if (deadlineNanos == null) return channel.lock()
        while (true) {
            val remaining = remainingNanos(deadlineNanos)
            val acquired = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            acquired?.let { return it }
            LockSupport.parkNanos(minOf(remaining, FILE_LOCK_POLL_NANOS))
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt()
                throw lockInterrupted(InterruptedException("SIWC credential lock wait was interrupted"))
            }
        }
    }

    private fun lockInterrupted(cause: InterruptedException) = OpenAiSiwcException(
        code = "credential_lock_interrupted",
        message = "ChatGPT credential access was interrupted.",
        retryable = true,
        cause = cause,
    )

    private fun prepareDirectory() {
        Files.createDirectories(directory)
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory)) {
            "SIWC credential directory must be a real directory"
        }
        restrictDirectory(directory)
    }

    private fun readState(): OpenAiSiwcStoredState? {
        if (!Files.exists(statePath, LinkOption.NOFOLLOW_LINKS)) return null
        val envelope = readPublic<EncryptedEnvelope>(statePath) ?: return null
        require(envelope.version == 1) { "Unsupported SIWC credential format" }
        val nonce = decode(envelope.nonce)
        require(nonce.size == NONCE_BYTES) { "Invalid SIWC credential nonce" }
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(AAD)
        val plaintext = try {
            cipher.doFinal(decode(envelope.ciphertext))
        } catch (error: Exception) {
            throw OpenAiSiwcException("credential_decryption_failed", "Saved ChatGPT credentials could not be decrypted.", cause = error)
        }
        return try {
            json.decodeFromString(OpenAiSiwcStoredState.serializer(), plaintext.toString(StandardCharsets.UTF_8)).also {
                require(it.version == 1) { "Unsupported SIWC state version" }
            }
        } catch (error: Exception) {
            throw OpenAiSiwcException("credential_state_invalid", "Saved ChatGPT credentials are invalid.", cause = error)
        }
    }

    private fun writeState(state: OpenAiSiwcStoredState) {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(AAD)
        val plaintext = json.encodeToString(OpenAiSiwcStoredState.serializer(), state).toByteArray(StandardCharsets.UTF_8)
        val envelope = EncryptedEnvelope(
            nonce = Base64.getEncoder().encodeToString(nonce),
            ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)),
        )
        writePublic(statePath, json.encodeToString(EncryptedEnvelope.serializer(), envelope))
    }

    private fun key(): ByteArray {
        if (Files.exists(keyPath, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(keyPath)) { "SIWC credential key must not be a symbolic link" }
            restrictFile(keyPath)
            return Files.readAllBytes(keyPath).also { require(it.size == KEY_BYTES) { "Invalid SIWC credential key" } }
        }
        val created = ByteArray(KEY_BYTES).also(random::nextBytes)
        try {
            FileChannel.open(keyPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                restrictFile(keyPath)
                channel.writeFully(created)
                channel.force(true)
            }
            forceDirectory()
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            restrictFile(keyPath)
            return Files.readAllBytes(keyPath).also { require(it.size == KEY_BYTES) { "Invalid SIWC credential key" } }
        }
        return created
    }

    private inline fun <reified T> readPublic(path: Path): T? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        require(!Files.isSymbolicLink(path)) { "SIWC credential file must not be a symbolic link" }
        restrictFile(path)
        val bytes = Files.readAllBytes(path)
        require(bytes.size <= MAX_FILE_BYTES) { "SIWC credential file is too large" }
        return json.decodeFromString(bytes.toString(StandardCharsets.UTF_8))
    }

    private fun writePublic(path: Path, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "SIWC credential state is too large" }
        val temporary = directory.resolve(".${path.fileName}.${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                restrictFile(temporary)
                channel.writeFully(bytes)
                channel.force(true)
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            restrictFile(path)
            forceDirectory()
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun restrictFile(path: Path) {
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
            Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
    }

    private fun restrictDirectory(path: Path) {
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
            Files.setPosixFilePermissions(path, setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            ))
        }
    }

    private fun forceDirectory() {
        if (Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) != null) {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun FileChannel.writeFully(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) write(buffer)
    }

    private fun decode(value: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (error: IllegalArgumentException) {
        throw OpenAiSiwcException("credential_state_invalid", "Saved ChatGPT credentials are invalid.", cause = error)
    }

    @Serializable
    private data class EncryptedEnvelope(val version: Int = 1, val nonce: String, val ciphertext: String)

    @Serializable
    private data class HostIdentity(val version: Int = 1, val id: String)

    private companion object {
        const val MAX_FILE_BYTES = 1024 * 1024
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        val FILE_LOCK_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10)
        const val CIPHER = "AES/GCM/NoPadding"
        val AAD = "gromozeka-openai-siwc-v1".toByteArray(StandardCharsets.UTF_8)
    }
}
