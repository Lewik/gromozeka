package com.gromozeka.presentation.services

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.domain.model.ArtifactUpload
import com.gromozeka.presentation.services.translation.LocalizedText
import com.gromozeka.presentation.services.translation.LocalizedTextException
import com.gromozeka.presentation.services.translation.localizedText
import com.gromozeka.presentation.services.translation.localizedPlural
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.JsFun
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.Promise

@OptIn(ExperimentalEncodingApi::class)
class BrowserAttachmentAcquisitionController : AttachmentAcquisitionController {
    override val capabilities = AttachmentAcquisitionCapabilities(
        filePicker = true,
        screenshot = browserDisplayCaptureSupported(),
        clipboardPaste = browserClipboardReadSupported(),
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val fileReader = createBrowserAttachmentReader(
        ArtifactLimits.MAX_FILE_BYTES,
        ArtifactLimits.MAX_ARTIFACTS_PER_MESSAGE,
        ArtifactLimits.MAX_TOTAL_BYTES_PER_MESSAGE.toDouble(),
    )

    override suspend fun pickAttachments(): List<ArtifactUpload> = uploadsFrom(pickBrowserAttachments(fileReader))

    override fun prepareClipboardAttachments(): AttachmentAcquisition? {
        if (!capabilities.clipboardPaste) return null
        val pending = readBrowserClipboardAttachments(fileReader)
        return { uploadsFrom(pending) }
    }

    internal fun prepareExternalFiles(files: JsAny): AttachmentAcquisition {
        val pending = readBrowserAttachmentFiles(fileReader, files)
        return { uploadsFrom(pending) }
    }

    private suspend fun uploadsFrom(pending: Promise<JsAny?>): List<ArtifactUpload> {
        val payload = json.decodeFromString<BrowserAttachmentPayload>(pending.await().toString())
        payload.error?.let { throw LocalizedTextException(it.localizedText()) }
        return payload.files.map(::toArtifactUpload)
    }

    override suspend fun captureScreenshot(): ArtifactUpload? {
        val payload = json.decodeFromString<BrowserScreenshotPayload>(
            captureBrowserScreenshot().await().toString()
        )
        payload.error?.let { throw LocalizedTextException(it.localizedText()) }
        val dataUrl = payload.dataUrl ?: return null
        return ArtifactUpload(
            fileName = "screenshot-${Clock.System.now().toEpochMilliseconds()}.png",
            mediaType = "image/png",
            content = Base64.Default.decode(dataUrl.substringAfter("base64,")),
            purpose = Artifact.Purpose.USER_SCREENSHOT,
        )
    }

    private fun toArtifactUpload(file: BrowserFile): ArtifactUpload =
        ArtifactUpload(
            fileName = file.name,
            mediaType = file.type.ifBlank { fallbackMediaType(file.name) },
            content = Base64.Default.decode(file.dataUrl.substringAfter("base64,")),
            purpose = Artifact.Purpose.USER_ATTACHMENT,
        )

    private fun fallbackMediaType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
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

@Serializable
private data class BrowserFile(
    val name: String,
    val type: String,
    val dataUrl: String,
)

@Serializable
private data class BrowserAttachmentPayload(
    val files: List<BrowserFile> = emptyList(),
    val error: BrowserAttachmentFailure? = null,
)

@Serializable
private data class BrowserScreenshotPayload(
    val dataUrl: String? = null,
    val error: BrowserAttachmentFailure? = null,
)

@Serializable
private data class BrowserAttachmentFailure(
    val code: BrowserAttachmentFailureCode,
    val fileName: String? = null,
    val diagnostic: String? = null,
) {
    fun localizedText(): LocalizedText {
        val reason = when (code) {
            BrowserAttachmentFailureCode.FILE_TOO_LARGE -> localizedText(
                "client.attachment.fileTooLarge",
                "file" to (fileName?.let(LocalizedText::Literal) ?: localizedText("chat.attachment.generic")),
                "limit" to ArtifactLimits.MAX_FILE_BYTES / (1024 * 1024),
            )
            BrowserAttachmentFailureCode.FILES_LIMIT -> localizedPlural(
                "client.attachment.tooMany", ArtifactLimits.MAX_ARTIFACTS_PER_MESSAGE.toLong(),
            )
            BrowserAttachmentFailureCode.TOTAL_SIZE_LIMIT -> localizedText(
                "client.attachment.totalTooLarge",
                "limit" to ArtifactLimits.MAX_TOTAL_BYTES_PER_MESSAGE / (1024 * 1024),
            )
            BrowserAttachmentFailureCode.FILE_READ_FAILED -> localizedText(
                "client.attachment.readFailed",
                "file" to (fileName?.let(LocalizedText::Literal) ?: localizedText("chat.attachment.generic")),
            )
            BrowserAttachmentFailureCode.ATTACHMENT_FAILED -> localizedText("client.attachment.failed")
            BrowserAttachmentFailureCode.SCREENSHOT_UNAVAILABLE -> localizedText("client.attachment.screenshotUnavailable")
            BrowserAttachmentFailureCode.SCREENSHOT_FAILED -> localizedText("client.attachment.screenshotFailed")
        }
        return attachmentFailureText(reason, diagnostic)
    }
}

@Serializable
private enum class BrowserAttachmentFailureCode {
    FILE_TOO_LARGE,
    FILES_LIMIT,
    TOTAL_SIZE_LIMIT,
    FILE_READ_FAILED,
    ATTACHMENT_FAILED,
    SCREENSHOT_UNAVAILABLE,
    SCREENSHOT_FAILED,
}

@JsFun(
    """
    (maxBytes, maxFiles, maxTotalBytes) => {
        const readFile = file => new Promise((resolve, reject) => {
            const fileName = file.name || ("pasted-" + Date.now());
            if (file.size > maxBytes) {
                reject({ code: "FILE_TOO_LARGE", fileName });
                return;
            }
            const reader = new FileReader();
            reader.onload = () => resolve({
                name: fileName, type: file.type || "", dataUrl: String(reader.result || "")
            });
            reader.onerror = () => reject({
                code: "FILE_READ_FAILED", fileName, diagnostic: reader.error?.message || null
            });
            reader.readAsDataURL(file);
        });
        return async fileList => {
            const files = Array.from(fileList || []);
            try {
                if (files.length > maxFiles) throw { code: "FILES_LIMIT" };
                if (files.reduce((sum, file) => sum + file.size, 0) > maxTotalBytes)
                    throw { code: "TOTAL_SIZE_LIMIT" };
                const results = [];
                for (const file of files) results.push(await readFile(file));
                return JSON.stringify({ files: results });
            } catch (error) {
                const known = ["FILE_TOO_LARGE", "FILES_LIMIT", "TOTAL_SIZE_LIMIT", "FILE_READ_FAILED"];
                return JSON.stringify({ files: [], error: known.includes(error?.code) ? error : {
                    code: "ATTACHMENT_FAILED", diagnostic: error instanceof Error ? error.message : String(error)
                }});
            }
        };
    }
    """
)
private external fun createBrowserAttachmentReader(maxBytes: Int, maxFiles: Int, maxTotalBytes: Double): JsAny

@JsFun("(reader, files) => reader(files)")
private external fun readBrowserAttachmentFiles(reader: JsAny, files: JsAny): Promise<JsAny?>

@JsFun(
    """
    reader => new Promise(resolve => {
        const input = document.createElement("input");
        input.type = "file";
        input.multiple = true;
        input.style.display = "none";
        let settled = false;
        const finish = value => {
            if (settled) return;
            settled = true;
            input.remove();
            resolve(value);
        };
        input.addEventListener("change", async () => finish(await reader(input.files)), { once: true });
        input.addEventListener("cancel", () => finish(JSON.stringify({ files: [] })), { once: true });
        document.body.appendChild(input);
        input.click();
    })
    """
)
private external fun pickBrowserAttachments(reader: JsAny): Promise<JsAny?>

@JsFun("() => !!(navigator.clipboard && navigator.clipboard.read)")
private external fun browserClipboardReadSupported(): Boolean

@JsFun(
    """
    async reader => {
        try {
            const items = await navigator.clipboard.read();
            const files = [];
            for (const item of items) {
                const type = item.types.find(type => type.startsWith("image/")) ||
                    item.types.find(type => !type.startsWith("text/"));
                if (!type) continue;
                const blob = await item.getType(type);
                const extension = {"image/png":"png", "image/jpeg":"jpg", "image/gif":"gif",
                    "image/webp":"webp", "image/heic":"heic", "application/pdf":"pdf"}[type] || "bin";
                files.push(new File([blob], "clipboard-" + Date.now() + "-" + files.length + "." + extension,
                    { type }));
            }
            return await reader(files);
        } catch (error) {
            return JSON.stringify({ files: [], error: {
                code: "ATTACHMENT_FAILED", diagnostic: error instanceof Error ? error.message : String(error)
            }});
        }
    }
    """
)
private external fun readBrowserClipboardAttachments(reader: JsAny): Promise<JsAny?>

@JsFun(
    """
    () => !!(navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia)
    """
)
private external fun browserDisplayCaptureSupported(): Boolean

@JsFun(
    """
    async () => {
        if (!navigator.mediaDevices?.getDisplayMedia) {
            return JSON.stringify({ error: { code: "SCREENSHOT_UNAVAILABLE" } });
        }
        let stream = null;
        try {
            stream = await navigator.mediaDevices.getDisplayMedia({
                video: { frameRate: 1 },
                audio: false
            });
            const video = document.createElement("video");
            video.muted = true;
            video.playsInline = true;
            video.srcObject = stream;
            await video.play();
            if (!video.videoWidth || !video.videoHeight) {
                await new Promise(resolve => video.addEventListener("loadedmetadata", resolve, { once: true }));
            }
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const canvas = document.createElement("canvas");
            canvas.width = video.videoWidth;
            canvas.height = video.videoHeight;
            const context = canvas.getContext("2d");
            if (!context) return JSON.stringify({ error: { code: "SCREENSHOT_FAILED" } });
            context.drawImage(video, 0, 0, canvas.width, canvas.height);
            const dataUrl = await new Promise((resolve, reject) => {
                canvas.toBlob(blob => {
                    if (!blob) {
                        reject({ code: "SCREENSHOT_FAILED" });
                        return;
                    }
                    const reader = new FileReader();
                    reader.onload = () => resolve(String(reader.result || ""));
                    reader.onerror = () => reject({
                        code: "SCREENSHOT_FAILED", diagnostic: reader.error?.message || null
                    });
                    try {
                        reader.readAsDataURL(blob);
                    } catch (error) {
                        reject(error);
                    }
                }, "image/png");
            });
            return JSON.stringify({ dataUrl });
        } catch (error) {
            if (error?.name === "NotAllowedError" || error?.name === "AbortError") return JSON.stringify({});
            return JSON.stringify({
                error: error?.code === "SCREENSHOT_FAILED" ? error : {
                    code: "SCREENSHOT_FAILED",
                    diagnostic: error instanceof Error ? error.message : String(error)
                }
            });
        } finally {
            stream?.getTracks().forEach(track => track.stop());
        }
    }
    """
)
private external fun captureBrowserScreenshot(): Promise<JsAny?>
