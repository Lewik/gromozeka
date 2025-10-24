package com.gromozeka.bot.services

import com.exceptionfactory.jagged.EncryptingChannelFactory
import com.exceptionfactory.jagged.RecipientStanzaWriter
import com.exceptionfactory.jagged.framework.stream.StandardEncryptingChannelFactory
import com.exceptionfactory.jagged.x25519.X25519RecipientStanzaWriterFactory
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.StandardOpenOption
import com.gromozeka.bot.settings.AppMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*

/**
 * Simple log encryption utility for secure bug reporting.
 * 
 * Workflow:
 * 1. Create ZIP archive (logs already sanitized by StreamMessageSanitizer)
 * 2. Age encrypt with X25519 + ChaCha20-Poly1305
 * 3. Save to Gromozeka home directory for user to share
 */
@Service
class LogEncryptor(
    private val settingsService: SettingsService
) {
    
    companion object {
        private const val DEV_PUBLIC_KEY_RESOURCE = "/encryption/developer_public.age"
    }
    
    data class EncryptionResult(
        val success: Boolean,
        val encryptedFile: Path? = null,
        val error: String? = null
    )
    
    /**
     * Main method: Create encrypted logs package for bug reporting
     */
    suspend fun encryptLogs(): EncryptionResult = withContext(Dispatchers.IO) {
        try {
            val logsPath = settingsService.getLogsDirectory()
            if (!logsPath.exists()) {
                return@withContext EncryptionResult(false, error = "Logs directory not found: $logsPath")
            }
            
            // Generate single timestamp for consistency across all files
            val timestamp = generateUtcTimestamp()
            
            // Step 1: Create ZIP archive (logs already sanitized)
            val zipFile = createLogsZip(logsPath, timestamp)
            
            // Step 2: Age encryption with X25519 + ChaCha20-Poly1305
            val encryptedFile = encryptWithAge(zipFile)
            
            // Step 3: Move to Gromozeka home directory
            val finalFile = moveToGromozemkaHome(encryptedFile)
            
            // Cleanup temp file
            Files.deleteIfExists(zipFile)
            if (encryptedFile != finalFile) {
                Files.deleteIfExists(encryptedFile)
            }
            
            EncryptionResult(success = true, encryptedFile = finalFile)
            
        } catch (e: Exception) {
            EncryptionResult(success = false, error = "Encryption failed: ${e.message}")
        }
    }
    
    /**
     * Create ZIP archive with logs (already sanitized by StreamMessageSanitizer)
     */
    private suspend fun createLogsZip(logsPath: Path, timestamp: String): Path {
        val zipFile = Files.createTempFile("gromozeka-logs-$timestamp", ".zip")
        
        ZipOutputStream(Files.newOutputStream(zipFile)).use { zip ->
            Files.walk(logsPath)
                .filter { Files.isRegularFile(it) }
                .forEach { logFile ->
                    val relativePath = logsPath.relativize(logFile).toString()
                    zip.putNextEntry(ZipEntry("logs/$relativePath"))
                    
                    // Copy all files as-is (logs already sanitized at write time)
                    Files.copy(logFile, zip)
                    zip.closeEntry()
                }
        }
        
        return zipFile
    }
    
    
    /**
     * Encrypt ZIP with Age using X25519 + ChaCha20-Poly1305
     */
    private suspend fun encryptWithAge(zipFile: Path): Path = withContext(Dispatchers.IO) {
        val encryptedFile = zipFile.parent.resolve("${zipFile.nameWithoutExtension}.age")
        
        // Load Age public key from resources
        val publicKeyString = loadDeveloperPublicKey()
        
        // Create Age recipient writer from public key
        val stanzaWriter = X25519RecipientStanzaWriterFactory.newRecipientStanzaWriter(publicKeyString)
        val channelFactory = StandardEncryptingChannelFactory()
        
        // Encrypt the ZIP file using Age
        Files.newByteChannel(zipFile).use { inputChannel ->
            Files.newByteChannel(
                encryptedFile, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            ).use { outputChannel ->
                channelFactory.newEncryptingChannel(
                    outputChannel,
                    listOf(stanzaWriter)
                ).use { encryptingChannel ->
                    // Copy data through encrypting channel
                    copyChannel(inputChannel, encryptingChannel)
                }
            }
        }
        
        return@withContext encryptedFile
    }
    
    /**
     * Copy data between channels (utility method)
     */
    private fun copyChannel(input: ReadableByteChannel, output: WritableByteChannel) {
        val buffer = java.nio.ByteBuffer.allocate(8192)
        while (input.read(buffer) != -1) {
            buffer.flip()
            while (buffer.hasRemaining()) {
                output.write(buffer)
            }
            buffer.clear()
        }
    }
    
    /**
     * Move encrypted file to Gromozeka home directory
     */
    private fun moveToGromozemkaHome(encryptedFile: Path): Path {
        val targetDir = settingsService.gromozekaHome.toPath()
            .resolve("encrypted-logs")
        
        // Ensure directory exists
        Files.createDirectories(targetDir)
        
        val finalFile = targetDir.resolve(encryptedFile.fileName)
        
        Files.move(encryptedFile, finalFile, StandardCopyOption.REPLACE_EXISTING)
        return finalFile
    }
    
    /**
     * Generate UTC timestamp in ISO 8601 format adapted for file names
     * Format: 2025-09-20T14-05-30Z (ISO 8601 with : replaced by - for Windows compatibility)
     */
    private fun generateUtcTimestamp(): String {
        return Instant.now()
            .atZone(ZoneOffset.UTC)
            .toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'"))
    }
    
    /**
     * Load developer public key from resources
     */
    private fun loadDeveloperPublicKey(): String {
        val keyStream = this::class.java.getResourceAsStream(DEV_PUBLIC_KEY_RESOURCE)
            ?: throw IllegalStateException("Developer public key not found in resources: $DEV_PUBLIC_KEY_RESOURCE")
        
        return keyStream.use { stream ->
            String(stream.readBytes()).trim()
        }
    }
    
}