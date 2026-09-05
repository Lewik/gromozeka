package com.gromozeka.worker

import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.shared.utils.sha256
import com.gromozeka.worker.runtime.WorkerRequestJournal
import com.gromozeka.worker.runtime.WorkerRequestReceipt
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Service
class JvmWorkerRequestJournal(
    settings: SettingsProvider,
    identity: ConversationRuntimeWorkerIdentity,
) : WorkerRequestJournal {
    private val directory = Path.of(settings.homeDirectory, "worker-requests", identity.workerId.value.sha256())
    private val random = SecureRandom()
    private val json = Json { encodeDefaults = true }
    private val lockChannel: FileChannel
    private val lock: java.nio.channels.FileLock
    private val key: SecretKeySpec

    init {
        Files.createDirectories(directory)
        restrict(directory, "rwx------")
        lockChannel = FileChannel.open(directory.resolve("journal.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            lock = checkNotNull(lockChannel.tryLock()) { "Another process owns this Worker's request journal" }
            val keyPath = directory.resolve("journal.key")
            if (!Files.exists(keyPath)) atomicWrite(keyPath, ByteArray(32).also(random::nextBytes))
            key = SecretKeySpec(Files.readAllBytes(keyPath).also { require(it.size == 32) }, "AES")
        } catch (error: Throwable) {
            lockChannel.close()
            throw error
        }
    }

    override suspend fun load(): List<WorkerRequestReceipt> = withContext(Dispatchers.IO) {
        Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".receipt") }.map { path ->
                val stored = Files.readAllBytes(path)
                require(stored.size >= 29 && stored[0] == 1.toByte()) { "Invalid Worker receipt format" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, stored.copyOfRange(1, 13)))
                cipher.updateAAD(path.fileName.toString().encodeToByteArray())
                json.decodeFromString<WorkerRequestReceipt>(cipher.doFinal(stored.copyOfRange(13, stored.size)).decodeToString())
                    .also { require(path.fileName.toString() == "${it.id.sha256()}.receipt") }
            }.toList()
        }
    }

    override suspend fun save(receipt: WorkerRequestReceipt): Unit = withContext(Dispatchers.IO) {
        val path = directory.resolve("${receipt.id.sha256()}.receipt")
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(path.fileName.toString().encodeToByteArray())
        atomicWrite(path, byteArrayOf(1) + nonce + cipher.doFinal(json.encodeToString(receipt).encodeToByteArray()))
    }

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        Files.deleteIfExists(directory.resolve("${id.sha256()}.receipt"))
        syncDirectory()
    }

    private fun atomicWrite(path: Path, bytes: ByteArray) {
        val temporary = Files.createTempFile(directory, "receipt-", ".tmp")
        try {
            restrict(temporary, "rw-------")
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory()
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun syncDirectory() {
        if (Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) != null) {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun restrict(path: Path, permissions: String) {
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
        }
    }

    @PreDestroy
    fun close() {
        lock.release()
        lockChannel.close()
    }
}
