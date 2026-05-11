package com.gromozeka.presentation.services

import klog.KLoggers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlin.JsFun
import kotlin.js.Promise

class BrowserClientAudioRecorder : ClientAudioRecorder {
    private val log = KLoggers.logger(this)

    override suspend fun start(scope: CoroutineScope): ClientAudioRecordingSession {
        log.info { "Browser audio recorder session requested: ${browserAudioDebugInfo()}" }
        val session = BrowserClientAudioRecordingSession()
        session.start()
        return session
    }
}

private class BrowserClientAudioRecordingSession : ClientAudioRecordingSession {
    private val log = KLoggers.logger(this)
    private val started = CompletableDeferred<Unit>()
    private val stopped = CompletableDeferred<ClientRecordedAudio>()
    private var mediaRecorder: JsAny? = null

    suspend fun start() {
        log.info { "Browser audio recorder start requested: ${browserAudioDebugInfo()}" }
        runCatching {
            mediaRecorder = startBrowserAudioRecording(
                onStarted = {
                    log.info { "Browser audio recorder started" }
                    started.complete(Unit)
                },
                onStopped = { dataUrl: String, mediaType: String, byteSize: Int ->
                    log.info { "Browser audio recorder stopped: bytes=$byteSize mediaType=$mediaType" }
                    val base64 = dataUrl.substringAfter("base64,", "")
                    stopped.complete(
                        ClientRecordedAudio(
                            dataBase64 = base64,
                            mediaType = mediaType.ifBlank { "audio/webm" },
                            fileExtension = extensionForMediaType(mediaType),
                            byteSize = byteSize
                        )
                    )
                },
                onError = { message: String ->
                    log.warn { "Browser audio recorder error: $message; ${browserAudioDebugInfo()}" }
                    val error = IllegalStateException(message)
                    started.completeExceptionally(error)
                    stopped.completeExceptionally(error)
                }
            ).await()
            started.await()
        }.onFailure { error ->
            started.completeExceptionally(error)
            stopped.completeExceptionally(error)
        }.getOrThrow()
    }

    override suspend fun stop(): ClientRecordedAudio {
        log.info { "Browser audio recorder stop requested" }
        started.await()
        stopBrowserAudioRecording(mediaRecorder)
        return stopped.await()
    }

    override fun cancel() {
        log.info { "Browser audio recorder cancel requested" }
        runCatching { cancelBrowserAudioRecording(mediaRecorder) }
        val error = IllegalStateException("Browser audio recording cancelled")
        started.completeExceptionally(error)
        stopped.completeExceptionally(error)
    }
}

private fun extensionForMediaType(mediaType: String): String =
    when {
        "webm" in mediaType -> "webm"
        "mp4" in mediaType -> "m4a"
        "mpeg" in mediaType -> "mp3"
        "wav" in mediaType -> "wav"
        else -> "webm"
    }

@JsFun(
    """
    () => {
        const hasNavigator = typeof navigator !== "undefined";
        const mediaDevices = hasNavigator ? navigator.mediaDevices : undefined;
        const hasMediaDevices = !!mediaDevices;
        const hasGetUserMedia = !!(mediaDevices && mediaDevices.getUserMedia);
        const hasMediaRecorder = typeof MediaRecorder !== "undefined";
        const secureContext = typeof window !== "undefined" ? window.isSecureContext : false;
        const protocol = typeof location !== "undefined" ? location.protocol : "";
        const host = typeof location !== "undefined" ? location.host : "";
        return "secureContext=" + secureContext +
            " hasMediaDevices=" + hasMediaDevices +
            " hasGetUserMedia=" + hasGetUserMedia +
            " hasMediaRecorder=" + hasMediaRecorder +
            " protocol=" + protocol +
            " host=" + host;
    }
    """
)
private external fun browserAudioDebugInfo(): String

@JsFun(
    """
    (onStarted, onStopped, onError) => {
        return (async () => {
            try {
                if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                    throw new Error("Browser microphone API is not available");
                }

                const preferredTypes = [
                    "audio/webm;codecs=opus",
                    "audio/webm",
                    "audio/mp4",
                    ""
                ];
                const mimeType = preferredTypes.find(type => !type || MediaRecorder.isTypeSupported(type)) || "";
                const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
                const chunks = [];
                const options = mimeType ? { mimeType } : undefined;
                const recorder = new MediaRecorder(stream, options);

                recorder.ondataavailable = event => {
                    if (event.data && event.data.size > 0) {
                        chunks.push(event.data);
                    }
                };
                recorder.onerror = event => {
                    onError(event.error?.message || "Browser audio recording failed");
                };
                recorder.onstop = () => {
                    const type = recorder.mimeType || mimeType || "audio/webm";
                    const blob = new Blob(chunks, { type });
                    const reader = new FileReader();
                    reader.onloadend = () => {
                        stream.getTracks().forEach(track => track.stop());
                        onStopped(String(reader.result || ""), type, blob.size);
                    };
                    reader.onerror = () => {
                        stream.getTracks().forEach(track => track.stop());
                        onError(reader.error?.message || "Failed to read recorded audio");
                    };
                    reader.readAsDataURL(blob);
                };

                recorder.start();
                onStarted();
                return { recorder, stream };
            } catch (error) {
                onError(error?.message || String(error));
                return null;
            }
        })();
    }
    """
)
private external fun startBrowserAudioRecording(
    onStarted: () -> Unit,
    onStopped: (String, String, Int) -> Unit,
    onError: (String) -> Unit,
): Promise<JsAny?>

@JsFun(
    """
    (handle) => {
        if (handle && handle.recorder && handle.recorder.state !== "inactive") {
            handle.recorder.stop();
        }
    }
    """
)
private external fun stopBrowserAudioRecording(handle: JsAny?)

@JsFun(
    """
    (handle) => {
        if (handle) {
            if (handle.recorder && handle.recorder.state !== "inactive") {
                handle.recorder.stop();
            }
            if (handle.stream) {
                handle.stream.getTracks().forEach(track => track.stop());
            }
        }
    }
    """
)
private external fun cancelBrowserAudioRecording(handle: JsAny?)
