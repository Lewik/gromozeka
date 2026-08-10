package com.gromozeka.presentation.services

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.domain.model.ArtifactUpload
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
                runCatching { json.decodeFromString<BrowserExternalAttachmentPayload>(payload) }
                    .onSuccess { external ->
                        val error = external.error
                        if (error != null) {
                            _externalEvents.emit(AttachmentAcquisitionEvent.Failed(error))
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
                                error.message ?: "Failed to read dropped files",
                            )
                        )
                    }
            }
        }
    }

    override suspend fun pickAttachments(): List<ArtifactUpload> =
        json.decodeFromString<List<BrowserFile>>(
            pickBrowserAttachments(ArtifactLimits.MAX_FILE_BYTES).await().toString()
        )
            .map(::toArtifactUpload)

    override suspend fun captureScreenshot(): ArtifactUpload? {
        val dataUrl = captureBrowserScreenshot().await()?.toString() ?: return null
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
private data class BrowserExternalAttachmentPayload(
    val files: List<BrowserFile> = emptyList(),
    val error: String? = null,
)

@JsFun(
    """
    maxBytes => new Promise((resolve, reject) => {
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
                readReject(new Error(file.name + " exceeds the " + Math.floor(maxBytes / 1048576) + " MB limit"));
                return;
            }
            const reader = new FileReader();
            reader.onload = () => readResolve({
                name: file.name,
                type: file.type || "",
                dataUrl: String(reader.result || "")
            });
            reader.onerror = () => readReject(reader.error || new Error("Failed to read selected file"));
            reader.readAsDataURL(file);
        });
        input.addEventListener("change", async () => {
            try {
                finish(JSON.stringify(await Promise.all(Array.from(input.files || []).map(readFile))));
            } catch (error) {
                input.remove();
                reject(error);
            }
        }, { once: true });
        input.addEventListener("cancel", () => finish("[]"), { once: true });
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
            if (file.size > maxBytes) {
                reject(new Error((file.name || "Attachment") + " exceeds the " + Math.floor(maxBytes / 1048576) + " MB limit"));
                return;
            }
            const reader = new FileReader();
            reader.onload = () => resolve({
                name: file.name || ("pasted-" + Date.now()),
                type: file.type || "",
                dataUrl: String(reader.result || "")
            });
            reader.onerror = () => reject(reader.error || new Error("Failed to read attached file"));
            reader.readAsDataURL(file);
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
                    error: error instanceof Error ? error.message : String(error),
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
            throw new Error("Screen capture is not supported by this browser");
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
            if (!context) throw new Error("Browser canvas is unavailable");
            context.drawImage(video, 0, 0, canvas.width, canvas.height);
            return await new Promise((resolve, reject) => {
                canvas.toBlob(blob => {
                    if (!blob) {
                        reject(new Error("Failed to encode screenshot"));
                        return;
                    }
                    const reader = new FileReader();
                    reader.onload = () => resolve(String(reader.result || ""));
                    reader.onerror = () => reject(reader.error || new Error("Failed to read screenshot"));
                    reader.readAsDataURL(blob);
                }, "image/png");
            });
        } catch (error) {
            if (error?.name === "NotAllowedError" || error?.name === "AbortError") return null;
            throw error;
        } finally {
            stream?.getTracks().forEach(track => track.stop());
        }
    }
    """
)
private external fun captureBrowserScreenshot(): Promise<JsAny?>
