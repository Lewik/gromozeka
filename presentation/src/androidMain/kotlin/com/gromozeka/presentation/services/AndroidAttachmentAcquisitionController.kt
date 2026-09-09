package com.gromozeka.presentation.services

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.domain.model.ArtifactUpload
import com.gromozeka.presentation.services.translation.LocalizedTextException
import com.gromozeka.presentation.services.translation.localizedText
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidAttachmentAcquisitionController(
    private val contentResolver: ContentResolver,
) : AttachmentAcquisitionController {
    override val capabilities = AttachmentAcquisitionCapabilities(
        filePicker = true,
        screenshot = false,
    )

    var launchFilePicker: (() -> Unit)? = null
    private var pendingPicker: CancellableContinuation<List<ArtifactUpload>>? = null

    override suspend fun pickAttachments(): List<ArtifactUpload> =
        suspendCancellableCoroutine { continuation ->
            if (pendingPicker != null) {
                throw LocalizedTextException(localizedText("client.attachment.filePickerAlreadyOpen"))
            }
            val launch = launchFilePicker
                ?: throw LocalizedTextException(localizedText("client.attachment.filePickerUnavailable"))
            pendingPicker = continuation
            continuation.invokeOnCancellation {
                if (pendingPicker === continuation) pendingPicker = null
            }
            launch()
        }

    override suspend fun captureScreenshot(): ArtifactUpload? = null

    fun onDocumentsPicked(uris: List<Uri>) {
        val continuation = pendingPicker ?: return
        pendingPicker = null
        CoroutineScope(continuation.context).launch(Dispatchers.IO) {
            runCatching { uris.map(::readUpload) }
                .onSuccess { uploads ->
                    if (continuation.isActive) continuation.resume(uploads)
                }
                .onFailure { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }
    }

    override fun close() {
        pendingPicker?.cancel()
        pendingPicker = null
    }

    private fun readUpload(uri: Uri): ArtifactUpload {
        var reportedSize: Long? = null
        val fileName = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            cursor.takeIf { it.moveToFirst() }?.let {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) reportedSize = cursor.getLong(sizeIndex)
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }
        } ?: uri.lastPathSegment ?: "attachment"
        if (reportedSize != null && reportedSize!! > ArtifactLimits.MAX_FILE_BYTES) {
            throw fileTooLarge(fileName)
        }
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                if (output.size() > ArtifactLimits.MAX_FILE_BYTES) throw fileTooLarge(fileName)
            }
            output.toByteArray()
        }
            ?: throw LocalizedTextException(localizedText("client.attachment.readFailed", "file" to fileName))
        return ArtifactUpload(
            fileName = fileName,
            mediaType = contentResolver.getType(uri) ?: fileName.fallbackMediaType(),
            content = bytes,
            purpose = Artifact.Purpose.USER_ATTACHMENT,
        )
    }

    private fun fileTooLarge(fileName: String): LocalizedTextException = LocalizedTextException(localizedText(
        "client.attachment.fileTooLarge",
        "file" to fileName,
        "limit" to ArtifactLimits.MAX_FILE_BYTES / (1024 * 1024),
    ))

    private fun String.fallbackMediaType(): String = when (substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "md", "txt", "kt", "kts", "java", "js", "ts", "tsx", "jsx", "py", "sh", "yaml", "yml", "xml", "csv" ->
            "text/plain"
        else -> "application/octet-stream"
    }
}
