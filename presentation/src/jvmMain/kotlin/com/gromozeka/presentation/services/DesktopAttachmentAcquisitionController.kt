package com.gromozeka.presentation.services

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.domain.model.ArtifactUpload
import com.gromozeka.presentation.services.translation.LocalizedTextException
import com.gromozeka.presentation.services.translation.localizedText
import com.gromozeka.presentation.services.translation.data.Translation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.coroutines.resume

class DesktopAttachmentAcquisitionController(
    private val currentTranslation: () -> Translation,
    private val clipboardContents: () -> Transferable? = {
        Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
    },
) : AttachmentAcquisitionController {
    override val capabilities = AttachmentAcquisitionCapabilities(
        filePicker = !GraphicsEnvironment.isHeadless(),
        screenshot = !GraphicsEnvironment.isHeadless(),
        clipboardPaste = !GraphicsEnvironment.isHeadless(),
    )

    override suspend fun pickAttachments(): List<ArtifactUpload> = uploadsFor(chooseFiles())

    override fun prepareClipboardAttachments(): AttachmentAcquisition? =
        clipboardContents()?.let(::prepareTransferable)

    internal fun canReceive(source: Transferable): Boolean =
        source.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
            source.isDataFlavorSupported(DataFlavor.imageFlavor) ||
            source.transferDataFlavors.any { it.isMimeTypeEqual("text/uri-list") }

    internal fun prepareTransferable(source: Transferable): AttachmentAcquisition? {
        if (source.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val offered = source.getTransferData(DataFlavor.javaFileListFlavor) as List<*>
            val files = offered.map { requireNotNull(it as? File) { "Invalid transferred file entry" } }.distinct()
            if (files.isNotEmpty()) return { uploadsFor(files) }
        }
        val uriFlavor = source.transferDataFlavors.firstOrNull { it.isMimeTypeEqual("text/uri-list") }
        if (uriFlavor != null) {
            val uris = uriFlavor.getReaderForText(source).use { reader ->
                val buffer = CharArray(65_537)
                var used = 0
                while (used < buffer.size) {
                    val count = reader.read(buffer, used, buffer.size - used)
                    if (count < 0) break
                    used += count
                }
                require(used < buffer.size) { "Transferred URI list exceeds 64 KiB" }
                String(buffer, 0, used).lineSequence().map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith('#') }.map(::URI).toList()
            }
            if (uris.isNotEmpty() && uris.all { it.scheme.equals("file", ignoreCase = true) }) {
                val files = uris.map(::File).distinct()
                return { uploadsFor(files) }
            }
        }
        if (source.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            val image = source.getTransferData(DataFlavor.imageFlavor) as Image
            return {
                withContext(Dispatchers.IO) {
                    val fileName = "clipboard-${System.currentTimeMillis()}.png"
                    val bytes = image.toBufferedImage().toPng()
                    if (bytes.size > ArtifactLimits.MAX_FILE_BYTES) throw fileTooLarge(fileName)
                    listOf(ArtifactUpload(fileName = fileName, mediaType = "image/png", content = bytes,
                        purpose = Artifact.Purpose.USER_ATTACHMENT))
                }
            }
        }
        return null
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
            val dialog = FileDialog(null as Frame?, currentTranslation().text("chat.input.attachFiles"), FileDialog.LOAD).apply {
                isMultipleMode = true
            }
            dialog.isVisible = true
            val files = dialog.files.toList()
            dialog.dispose()
            continuation.resume(files)
        }
    }

    private suspend fun uploadsFor(files: List<File>): List<ArtifactUpload> = withContext(Dispatchers.IO) {
        readAttachmentBatch(files) { file ->
            if (!file.isFile) {
                throw LocalizedTextException(localizedText("client.attachment.notAFile", "file" to file.name))
            }
            if (file.length() > ArtifactLimits.MAX_FILE_BYTES) throw fileTooLarge(file.name)
            val bytes = file.inputStream().use { it.readNBytes(ArtifactLimits.MAX_FILE_BYTES + 1) }
            if (bytes.size > ArtifactLimits.MAX_FILE_BYTES) throw fileTooLarge(file.name)
            ArtifactUpload(
                fileName = file.name,
                mediaType = file.detectMediaType(),
                content = bytes,
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
        return Robot().createScreenCapture(bounds).toPng()
    }

    private fun Image.toBufferedImage(): BufferedImage {
        if (this is BufferedImage) return this
        val width = getWidth(null)
        val height = getHeight(null)
        require(width > 0 && height > 0) { "Transferred image dimensions are unavailable" }
        return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { target ->
            val graphics = target.createGraphics()
            try {
                check(graphics.drawImage(this, 0, 0, null)) { "Transferred image could not be decoded" }
            } finally {
                graphics.dispose()
            }
        }
    }

    private fun BufferedImage.toPng(): ByteArray =
        ByteArrayOutputStream().use { output ->
            if (!ImageIO.write(this, "png", output)) {
                throw LocalizedTextException(localizedText("client.attachment.screenshotFailed"))
            }
            output.toByteArray()
        }

    private fun fileTooLarge(fileName: String) = LocalizedTextException(localizedText(
        "client.attachment.fileTooLarge", "file" to fileName,
        "limit" to ArtifactLimits.MAX_FILE_BYTES / (1024 * 1024),
    ))

    private fun File.detectMediaType(): String =
        Files.probeContentType(toPath()) ?: extension.lowercase().toFallbackMediaType()

    private fun String.toFallbackMediaType(): String = when (this) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "md", "txt", "kt", "kts", "java", "js", "ts", "tsx", "jsx", "py", "sh", "yaml", "yml", "xml", "csv" -> "text/plain"
        else -> "application/octet-stream"
    }
}
