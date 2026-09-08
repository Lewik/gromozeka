package com.gromozeka.presentation.services

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.domain.model.ArtifactUpload
import com.gromozeka.presentation.services.translation.LocalizedText
import com.gromozeka.presentation.services.translation.LocalizedTextException
import com.gromozeka.presentation.services.translation.localizedText
import kotlinx.coroutines.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _externalEvents = MutableSharedFlow<AttachmentAcquisitionEvent>(extraBufferCapacity = 8)
    override val externalEvents: Flow<AttachmentAcquisitionEvent> = _externalEvents.asSharedFlow()

    init {
        installBrowserExternalAttachmentBridge(ArtifactLimits.MAX_FILE_BYTES)
        scope.launch {
            while (isActive) {
                val payload = awaitBrowserExternalAttachments().await()?.toString() ?: break
                runCatching { json.decodeFromString<BrowserAttachmentPayload>(payload) }
                    .onSuccess { external ->
                        val error = external.error
                        if (error != null) {
                            _externalEvents.emit(AttachmentAcquisitionEvent.Failed(error.localizedText()))
                        } else {
                            _externalEvents.emit(
                                AttachmentAcquisitionEvent.Acquired(
                                    external.files.map(::toArtifactUpload),
                                )
                            )
                        }
                    }
                    .onFailure { error ->
                        _externalEvents.emit(
                            AttachmentAcquisitionEvent.Failed(
                                error.asAttachmentFailure(),
                            )
                        )
                    }
            }
        }
    }

    override suspend fun pickAttachments(): List<ArtifactUpload> {
        val payload = json.decodeFromString<BrowserAttachmentPayload>(
            pickBrowserAttachments(ArtifactLimits.MAX_FILE_BYTES).await().toString()
        )
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

    override fun close() {
        uninstallBrowserExternalAttachmentBridge()
        scope.cancel()
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
    FILE_READ_FAILED,
    ATTACHMENT_FAILED,
    SCREENSHOT_UNAVAILABLE,
    SCREENSHOT_FAILED,
}

@JsFun(
    """
    maxBytes => new Promise(resolve => {
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
        const readFile = file => new Promise((readResolve, readReject) => {
            if (file.size > maxBytes) {
                readReject({ code: "FILE_TOO_LARGE", fileName: file.name });
                return;
            }
            const reader = new FileReader();
            reader.onload = () => readResolve({
                name: file.name,
                type: file.type || "",
                dataUrl: String(reader.result || "")
            });
            reader.onerror = () => readReject({
                code: "FILE_READ_FAILED", fileName: file.name, diagnostic: reader.error?.message || null
            });
            try {
                reader.readAsDataURL(file);
            } catch (error) {
                readReject({
                    code: "FILE_READ_FAILED", fileName: file.name,
                    diagnostic: error instanceof Error ? error.message : String(error)
                });
            }
        });
        input.addEventListener("change", async () => {
            try {
                finish(JSON.stringify({ files: await Promise.all(Array.from(input.files || []).map(readFile)) }));
            } catch (error) {
                finish(JSON.stringify({
                    error: error?.code === "FILE_TOO_LARGE" || error?.code === "FILE_READ_FAILED" ? error : {
                        code: "ATTACHMENT_FAILED",
                        diagnostic: error instanceof Error ? error.message : String(error)
                    }
                }));
            }
        }, { once: true });
        input.addEventListener("cancel", () => finish(JSON.stringify({ files: [] })), { once: true });
        document.body.appendChild(input);
        input.click();
    })
    """
)
private external fun pickBrowserAttachments(maxBytes: Int): Promise<JsAny?>

@JsFun(
    """
    maxBytes => {
        const key = "__gromozekaExternalAttachments";
        if (globalThis[key]) return;

        const state = { queue: [], waiter: null };
        const readFile = file => new Promise((resolve, reject) => {
            const fileName = file.name || ("pasted-" + Date.now());
            if (file.size > maxBytes) {
                reject({ code: "FILE_TOO_LARGE", fileName });
                return;
            }
            const reader = new FileReader();
            reader.onload = () => resolve({
                name: fileName,
                type: file.type || "",
                dataUrl: String(reader.result || "")
            });
            reader.onerror = () => reject({
                code: "FILE_READ_FAILED", fileName, diagnostic: reader.error?.message || null
            });
            try {
                reader.readAsDataURL(file);
            } catch (error) {
                reject({
                    code: "FILE_READ_FAILED", fileName,
                    diagnostic: error instanceof Error ? error.message : String(error)
                });
            }
        });
        const deliver = payload => {
            if (state.waiter) {
                const waiter = state.waiter;
                state.waiter = null;
                waiter(payload);
            } else {
                state.queue.push(payload);
            }
        };
        const emitFiles = async fileList => {
            const files = Array.from(fileList || []);
            if (!files.length) return;
            try {
                deliver(JSON.stringify({ files: await Promise.all(files.map(readFile)) }));
            } catch (error) {
                deliver(JSON.stringify({
                    error: error?.code === "FILE_TOO_LARGE" || error?.code === "FILE_READ_FAILED" ? error : {
                        code: "ATTACHMENT_FAILED",
                        diagnostic: error instanceof Error ? error.message : String(error)
                    },
                    files: []
                }));
            }
        };
        const containsFiles = dataTransfer =>
            Array.from(dataTransfer?.types || []).includes("Files");

        state.dragover = event => {
            if (!containsFiles(event.dataTransfer)) return;
            event.preventDefault();
            event.dataTransfer.dropEffect = "copy";
        };
        state.drop = event => {
            if (!containsFiles(event.dataTransfer)) return;
            event.preventDefault();
            void emitFiles(event.dataTransfer.files);
        };
        state.paste = event => {
            const files = event.clipboardData?.files;
            if (!files?.length) return;
            event.preventDefault();
            void emitFiles(files);
        };

        document.addEventListener("dragover", state.dragover);
        document.addEventListener("drop", state.drop);
        document.addEventListener("paste", state.paste);
        globalThis[key] = state;
    }
    """
)
private external fun installBrowserExternalAttachmentBridge(maxBytes: Int)

@JsFun(
    """
    () => {
        const state = globalThis.__gromozekaExternalAttachments;
        if (!state) return Promise.resolve(null);
        if (state.queue.length) return Promise.resolve(state.queue.shift());
        return new Promise(resolve => { state.waiter = resolve; });
    }
    """
)
private external fun awaitBrowserExternalAttachments(): Promise<JsAny?>

@JsFun(
    """
    () => {
        const key = "__gromozekaExternalAttachments";
        const state = globalThis[key];
        if (!state) return;
        document.removeEventListener("dragover", state.dragover);
        document.removeEventListener("drop", state.drop);
        document.removeEventListener("paste", state.paste);
        state.waiter?.(null);
        delete globalThis[key];
    }
    """
)
private external fun uninstallBrowserExternalAttachmentBridge()

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
