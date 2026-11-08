@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.gromozeka.presentation.services

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.domain.model.ArtifactUpload
import com.gromozeka.presentation.services.translation.LocalizedTextException
import com.gromozeka.presentation.services.translation.localizedText
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.time.Clock
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSItemProvider
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeFileURL
import platform.UniformTypeIdentifiers.UTTypeURL
import platform.UniformTypeIdentifiers.UTTypeText
import platform.UniformTypeIdentifiers.conformsToType
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
        clipboardPaste = true,
    )

    private var documentDelegate: DocumentPickerDelegate? = null
    private var screenshotDelegate: ScreenshotPickerDelegate? = null

    override suspend fun pickAttachments(): List<ArtifactUpload> =
        suspendCancellableCoroutine { continuation ->
            if (documentDelegate != null) {
                throw LocalizedTextException(localizedText("client.attachment.filePickerAlreadyOpen"))
            }
            val viewController = visibleViewController()
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
            viewController.presentViewController(picker, animated = true, completion = null)
        }

    override suspend fun captureScreenshot(): ArtifactUpload? =
        suspendCancellableCoroutine { continuation ->
            if (screenshotDelegate != null) {
                throw LocalizedTextException(localizedText("client.attachment.screenshotPickerAlreadyOpen"))
            }
            val viewController = visibleViewController()
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
            viewController.presentViewController(picker, animated = true, completion = null)
        }

    override fun prepareClipboardAttachments(): AttachmentAcquisition? =
        prepareProviders(UIPasteboard.generalPasteboard.itemProviders.filterIsInstance<NSItemProvider>())

    internal fun canReceiveProvider(provider: NSItemProvider): Boolean = provider.attachmentType() != null

    internal fun prepareProviders(providers: List<NSItemProvider>): AttachmentAcquisition? {
        val accepted = providers.mapNotNull { provider -> provider.attachmentType()?.let { provider to it } }
        if (accepted.isEmpty()) return null
        return { readAttachmentBatch(accepted) { (provider, type) -> provider.readAttachment(type) } }
    }

    override fun close() {
        documentDelegate?.cancel()
        screenshotDelegate?.cancel()
        documentDelegate = null
        screenshotDelegate = null
    }
}

private fun NSItemProvider.attachmentType(): String? {
    if (hasItemConformingToTypeIdentifier(UTTypeFileURL.identifier)) return UTTypeFileURL.identifier
    val types = registeredTypeIdentifiers.filterIsInstance<String>()
    types.firstOrNull { UTType.typeWithIdentifier(it)?.conformsToType(UTTypeImage) == true }?.let { return it }
    // A suggested filename is not proof that copied text represents a file.
    return types.firstOrNull { identifier ->
        val type = UTType.typeWithIdentifier(identifier)
        type?.conformsToType(UTTypeData) == true &&
            !type.conformsToType(UTTypeURL) && !type.conformsToType(UTTypeText)
    }
}

private suspend fun NSItemProvider.readAttachment(typeIdentifier: String): ArtifactUpload =
    suspendCancellableCoroutine { continuation ->
        fun complete(result: Result<ArtifactUpload>) {
            if (!continuation.isActive) return
            result.onSuccess(continuation::resume).onFailure(continuation::resumeWithException)
        }
        if (typeIdentifier == UTTypeFileURL.identifier) {
            loadItemForTypeIdentifier(typeIdentifier, null) { item, error ->
                if (!continuation.isActive) return@loadItemForTypeIdentifier
                complete(runCatching {
                    if (error != null) throw providerReadFailure(suggestedName, error)
                    val url = when (item) {
                        is NSURL -> item
                        is NSData -> NSURL.URLWithString(item.toByteArray("file URL").decodeToString(throwOnInvalidSequence = true))
                        else -> null
                    } ?: throw unreadableFile(suggestedName ?: "attachment")
                    require(url.scheme == "file") { "Transferred URL is not a local file" }
                    val scoped = url.startAccessingSecurityScopedResource()
                    try {
                        providerFileUpload(url, suggestedName, null)
                    } finally {
                        if (scoped) url.stopAccessingSecurityScopedResource()
                    }
                })
            }
        } else {
            val progress = loadFileRepresentationForTypeIdentifier(typeIdentifier) { url, error ->
                if (!continuation.isActive) return@loadFileRepresentationForTypeIdentifier
                complete(runCatching {
                    if (error != null) throw providerReadFailure(suggestedName, error)
                    // NSItemProvider deletes this temporary file as soon as its callback returns.
                    providerFileUpload(url ?: throw unreadableFile(suggestedName ?: "attachment"), suggestedName, typeIdentifier)
                })
            }
            continuation.invokeOnCancellation { progress.cancel() }
        }
    }

private fun providerFileUpload(url: NSURL, suggestedName: String?, typeIdentifier: String?): ArtifactUpload {
    val type = typeIdentifier?.let(UTType::typeWithIdentifier)
    val fileName = suggestedName?.takeIf(String::isNotBlank)
        ?: url.lastPathComponent?.takeIf(String::isNotBlank)
        ?: "attachment-${Clock.System.now().toEpochMilliseconds()}.${type?.preferredFilenameExtension ?: "bin"}"
    val path = url.path ?: throw unreadableFile(fileName)
    return ArtifactUpload(
        fileName = fileName,
        mediaType = type?.preferredMIMEType ?: fileName.fallbackMediaType(),
        content = readFileBytes(path, fileName),
        purpose = Artifact.Purpose.USER_ATTACHMENT,
    )
}

private fun providerReadFailure(fileName: String?, error: NSError) = LocalizedTextException(attachmentFailureText(
    localizedText("client.attachment.readFailed", "file" to (fileName ?: "attachment")), error.localizedDescription,
))

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
                        val fileName = url.lastPathComponent ?: "attachment"
                        val path = url.path ?: throw unreadableFile(fileName)
                        val bytes = readFileBytes(path, fileName)
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
                fail(LocalizedTextException(localizedText("client.attachment.screenshotFailed")))
                return
            }

        val fileName = "screenshot-${Clock.System.now().toEpochMilliseconds()}.${typeIdentifier.fileExtensionForTypeIdentifier()}"
        provider.loadDataRepresentationForTypeIdentifier(typeIdentifier) { data: NSData?, error: NSError? ->
            when {
                error != null -> fail(LocalizedTextException(attachmentFailureText(
                    localizedText("client.attachment.screenshotFailed"),
                    error.localizedDescription,
                )))
                data == null -> fail(LocalizedTextException(localizedText("client.attachment.screenshotFailed")))
                else -> runCatching {
                    ArtifactUpload(
                        fileName = fileName,
                        mediaType = typeIdentifier.mediaTypeForTypeIdentifier(),
                        content = data.toByteArray(fileName),
                        purpose = Artifact.Purpose.USER_SCREENSHOT,
                    )
                }.onSuccess(::finish).onFailure(::fail)
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
        ?: throw LocalizedTextException(localizedText("client.attachment.activeWindowUnavailable"))
    var visible = root
    while (visible.presentedViewController != null) {
        visible = requireNotNull(visible.presentedViewController)
    }
    return visible
}

private fun NSData.toByteArray(fileName: String): ByteArray {
    if (length == 0uL) return ByteArray(0)
    if (length > ArtifactLimits.MAX_FILE_BYTES.toULong()) throw fileTooLarge(fileName)
    return ByteArray(length.toInt()).also { output ->
        output.usePinned { pinned ->
            memcpy(pinned.addressOf(0), bytes, length)
        }
    }
}

private fun readFileBytes(path: String, fileName: String): ByteArray {
    val file = fopen(path, "rb") ?: throw unreadableFile(fileName)
    try {
        if (fseek(file, 0, SEEK_END) != 0) throw unreadableFile(fileName)
        val size = ftell(file)
        if (size < 0) throw unreadableFile(fileName)
        if (size > ArtifactLimits.MAX_FILE_BYTES) throw fileTooLarge(fileName)
        rewind(file)
        if (size <= 0) return ByteArray(0)
        return ByteArray(size.toInt()).also { output ->
            output.usePinned { pinned ->
                if (fread(pinned.addressOf(0), 1u, size.toULong(), file) != size.toULong()) {
                    throw unreadableFile(fileName)
                }
            }
        }
    } finally {
        fclose(file)
    }
}

private fun unreadableFile(fileName: String): LocalizedTextException =
    LocalizedTextException(localizedText("client.attachment.readFailed", "file" to fileName))

private fun fileTooLarge(fileName: String): LocalizedTextException = LocalizedTextException(localizedText(
    "client.attachment.fileTooLarge",
    "file" to fileName,
    "limit" to ArtifactLimits.MAX_FILE_BYTES / (1024 * 1024),
))

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
