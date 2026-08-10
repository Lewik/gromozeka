@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.gromozeka.presentation.services

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.domain.model.ArtifactUpload
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.time.Clock
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject
import platform.posix.memcpy
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.rewind
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class IosAttachmentAcquisitionController : AttachmentAcquisitionController {
    override val capabilities = AttachmentAcquisitionCapabilities(
        filePicker = true,
        screenshot = true,
    )

    private var documentDelegate: DocumentPickerDelegate? = null
    private var screenshotDelegate: ScreenshotPickerDelegate? = null

    override suspend fun pickAttachments(): List<ArtifactUpload> =
        suspendCancellableCoroutine { continuation ->
            check(documentDelegate == null) { "The document picker is already open" }
            val picker = UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeData),
                asCopy = true,
            ).apply {
                allowsMultipleSelection = true
                shouldShowFileExtensions = true
            }
            val delegate = DocumentPickerDelegate(picker, continuation) {
                documentDelegate = null
            }
            documentDelegate = delegate
            picker.delegate = delegate
            continuation.invokeOnCancellation {
                picker.dismissViewControllerAnimated(true, null)
                documentDelegate = null
            }
            visibleViewController().presentViewController(picker, animated = true, completion = null)
        }

    override suspend fun captureScreenshot(): ArtifactUpload? =
        suspendCancellableCoroutine { continuation ->
            check(screenshotDelegate == null) { "The screenshot picker is already open" }
            val configuration = PHPickerConfiguration().apply {
                filter = PHPickerFilter.screenshotsFilter
                selectionLimit = 1
            }
            val picker = PHPickerViewController(configuration)
            val delegate = ScreenshotPickerDelegate(picker, continuation) {
                screenshotDelegate = null
            }
            screenshotDelegate = delegate
            picker.delegate = delegate
            continuation.invokeOnCancellation {
                picker.dismissViewControllerAnimated(true, null)
                screenshotDelegate = null
            }
            visibleViewController().presentViewController(picker, animated = true, completion = null)
        }

    override fun close() {
        documentDelegate?.cancel()
        screenshotDelegate?.cancel()
        documentDelegate = null
        screenshotDelegate = null
    }
}

private class DocumentPickerDelegate(
    private val picker: UIDocumentPickerViewController,
    private val continuation: CancellableContinuation<List<ArtifactUpload>>,
    private val onFinished: () -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        complete {
            didPickDocumentsAtURLs
                .filterIsInstance<NSURL>()
                .map { url ->
                    val scoped = url.startAccessingSecurityScopedResource()
                    try {
                        val path = url.path ?: error("Selected file has no local path")
                        val bytes = readFileBytes(path)
                        val fileName = url.lastPathComponent ?: "attachment"
                        ArtifactUpload(
                            fileName = fileName,
                            mediaType = fileName.fallbackMediaType(),
                            content = bytes,
                            purpose = Artifact.Purpose.USER_ATTACHMENT,
                        )
                    } finally {
                        if (scoped) url.stopAccessingSecurityScopedResource()
                    }
                }
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        complete { emptyList() }
    }

    fun cancel() {
        picker.dismissViewControllerAnimated(false, null)
        if (continuation.isActive) continuation.cancel()
    }

    private inline fun complete(result: () -> List<ArtifactUpload>) {
        picker.dismissViewControllerAnimated(true, null)
        onFinished()
        if (!continuation.isActive) return
        runCatching(result)
            .onSuccess(continuation::resume)
            .onFailure(continuation::resumeWithException)
    }
}

private class ScreenshotPickerDelegate(
    private val picker: PHPickerViewController,
    private val continuation: CancellableContinuation<ArtifactUpload?>,
    private val onFinished: () -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, null)
        val result = didFinishPicking.filterIsInstance<PHPickerResult>().firstOrNull()
        if (result == null) {
            finish(null)
            return
        }

        val provider = result.itemProvider
        val typeIdentifier = provider.registeredTypeIdentifiers
            .filterIsInstance<String>()
            .firstOrNull { it.contains("png", ignoreCase = true) }
            ?: provider.registeredTypeIdentifiers.filterIsInstance<String>().firstOrNull()
            ?: run {
                fail(IllegalStateException("Selected screenshot has no readable representation"))
                return
            }

        provider.loadDataRepresentationForTypeIdentifier(typeIdentifier) { data: NSData?, error: NSError? ->
            when {
                error != null -> fail(IllegalStateException(error.localizedDescription))
                data == null -> fail(IllegalStateException("Selected screenshot is not readable"))
                else -> finish(
                    ArtifactUpload(
                        fileName =
                            "screenshot-${Clock.System.now().toEpochMilliseconds()}.${typeIdentifier.fileExtensionForTypeIdentifier()}",
                        mediaType = typeIdentifier.mediaTypeForTypeIdentifier(),
                        content = data.toByteArray(),
                        purpose = Artifact.Purpose.USER_SCREENSHOT,
                    )
                )
            }
        }
    }

    fun cancel() {
        picker.dismissViewControllerAnimated(false, null)
        if (continuation.isActive) continuation.cancel()
    }

    private fun finish(upload: ArtifactUpload?) {
        onFinished()
        if (continuation.isActive) continuation.resume(upload)
    }

    private fun fail(error: Throwable) {
        onFinished()
        if (continuation.isActive) continuation.resumeWithException(error)
    }
}

private fun visibleViewController(): UIViewController {
    val root = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
        .firstOrNull { it.isKeyWindow() }
        ?.rootViewController
        ?: error("No active iOS window is available")
    var visible = root
    while (visible.presentedViewController != null) {
        visible = requireNotNull(visible.presentedViewController)
    }
    return visible
}

private fun NSData.toByteArray(): ByteArray {
    if (length == 0uL) return ByteArray(0)
    require(length <= ArtifactLimits.MAX_FILE_BYTES.toULong()) {
        "Selected screenshot exceeds the ${ArtifactLimits.MAX_FILE_BYTES / (1024 * 1024)} MB limit"
    }
    return ByteArray(length.toInt()).also { output ->
        output.usePinned { pinned ->
            memcpy(pinned.addressOf(0), bytes, length)
        }
    }
}

private fun readFileBytes(path: String): ByteArray {
    val file = fopen(path, "rb") ?: error("Selected file is not readable")
    try {
        check(fseek(file, 0, SEEK_END) == 0) { "Failed to seek selected file" }
        val size = ftell(file)
        require(size <= ArtifactLimits.MAX_FILE_BYTES) {
            "Selected file exceeds the ${ArtifactLimits.MAX_FILE_BYTES / (1024 * 1024)} MB limit"
        }
        rewind(file)
        if (size <= 0) return ByteArray(0)
        return ByteArray(size.toInt()).also { output ->
            output.usePinned { pinned ->
                fread(pinned.addressOf(0), 1u, size.toULong(), file)
            }
        }
    } finally {
        fclose(file)
    }
}

private fun String.fallbackMediaType(): String =
    substringAfterLast('.', "").lowercase().fallbackMediaTypeForExtension()

private fun String.mediaTypeForTypeIdentifier(): String = when {
    contains("jpeg", ignoreCase = true) || contains("jpg", ignoreCase = true) -> "image/jpeg"
    contains("heic", ignoreCase = true) -> "image/heic"
    else -> "image/png"
}

private fun String.fileExtensionForTypeIdentifier(): String = when {
    contains("jpeg", ignoreCase = true) || contains("jpg", ignoreCase = true) -> "jpg"
    contains("heic", ignoreCase = true) -> "heic"
    else -> "png"
}

private fun String.fallbackMediaTypeForExtension(): String = when (this) {
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
