package com.gromozeka.presentation.services

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.domain.model.ArtifactUpload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.coroutines.resume

class DesktopAttachmentAcquisitionController : AttachmentAcquisitionController {
    override val capabilities = AttachmentAcquisitionCapabilities(
        filePicker = !GraphicsEnvironment.isHeadless(),
        screenshot = !GraphicsEnvironment.isHeadless(),
    )
    private val _externalEvents = MutableSharedFlow<AttachmentAcquisitionEvent>(extraBufferCapacity = 8)
    override val externalEvents: Flow<AttachmentAcquisitionEvent> = _externalEvents.asSharedFlow()

    override suspend fun pickAttachments(): List<ArtifactUpload> {
        return uploadsFor(chooseFiles())
    }

    suspend fun acceptDroppedFiles(files: List<File>) {
        runCatching { uploadsFor(files) }
            .onSuccess { uploads ->
                _externalEvents.emit(AttachmentAcquisitionEvent.Acquired(uploads))
            }
            .onFailure { error ->
                _externalEvents.emit(
                    AttachmentAcquisitionEvent.Failed(
                        error.message ?: "Failed to read dropped files",
                    )
                )
            }
    }

    override suspend fun captureScreenshot(): ArtifactUpload? = withContext(Dispatchers.IO) {
        val bytes = if (System.getProperty("os.name").lowercase().contains("mac")) {
            captureMacSelection()
        } else {
            captureDesktop()
        } ?: return@withContext null

        ArtifactUpload(
            fileName = "screenshot-${System.currentTimeMillis()}.png",
            mediaType = "image/png",
            content = bytes,
            purpose = Artifact.Purpose.USER_SCREENSHOT,
        )
    }

    private suspend fun chooseFiles(): List<File> = suspendCancellableCoroutine { continuation ->
        EventQueue.invokeLater {
            val dialog = FileDialog(null as Frame?, "Attach files", FileDialog.LOAD).apply {
                isMultipleMode = true
            }
            dialog.isVisible = true
            val files = dialog.files.toList()
            dialog.dispose()
            continuation.resume(files)
        }
    }

    private suspend fun uploadsFor(files: List<File>): List<ArtifactUpload> = withContext(Dispatchers.IO) {
        files.map { file ->
            require(file.isFile) { "Not a file: ${file.name}" }
            require(file.length() <= ArtifactLimits.MAX_FILE_BYTES) {
                "${file.name} exceeds the ${ArtifactLimits.MAX_FILE_BYTES / (1024 * 1024)} MB limit"
            }
            ArtifactUpload(
                fileName = file.name,
                mediaType = file.detectMediaType(),
                content = file.readBytes(),
                purpose = Artifact.Purpose.USER_ATTACHMENT,
            )
        }
    }

    private fun captureMacSelection(): ByteArray? {
        val target = Files.createTempFile("gromozeka-screenshot-", ".png").toFile()
        return try {
            val exitCode = ProcessBuilder("screencapture", "-i", "-o", target.absolutePath)
                .start()
                .waitFor()
            target.takeIf { exitCode == 0 && it.length() > 0 }?.readBytes()
        } finally {
            target.delete()
        }
    }

    private fun captureDesktop(): ByteArray {
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .screenDevices
            .map { it.defaultConfiguration.bounds }
            .reduce(Rectangle::union)
        val image = Robot().createScreenCapture(bounds)
        return image.toPng()
    }

    private fun BufferedImage.toPng(): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(ImageIO.write(this, "png", output)) { "PNG encoder is unavailable" }
            output.toByteArray()
        }

    private fun File.detectMediaType(): String =
        Files.probeContentType(toPath())
            ?: extension.lowercase().toFallbackMediaType()

    private fun String.toFallbackMediaType(): String = when (this) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "md", "txt", "kt", "kts", "java", "js", "ts", "tsx", "jsx", "py", "sh", "yaml", "yml", "xml", "csv" ->
            "text/plain"
        else -> "application/octet-stream"
    }
}
