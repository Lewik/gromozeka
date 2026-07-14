package com.gromozeka.presentation.services

import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.shared.audio.SpeechPcmWav
import klog.KLoggers
import kotlin.JsFun
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.Promise
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await

class BrowserClientAudioRecorder : ClientAudioRecorder {
    private val log = KLoggers.logger(this)

    override suspend fun start(scope: CoroutineScope): ClientAudioRecordingSession {
        log.info { "Browser audio recorder session requested: ${browserAudioDebugInfo()}" }
        val session = BrowserClientAudioRecordingSession()
        session.start()
        return session
    }
}

@OptIn(ExperimentalEncodingApi::class)
private class BrowserClientAudioRecordingSession : ClientAudioRecordingSession {
    private val log = KLoggers.logger(this)
    private val started = CompletableDeferred<Unit>()
    private val stopped = CompletableDeferred<ClientRecordedAudio>()
    private var recordingHandle: JsAny? = null

    suspend fun start() {
        log.info { "Browser PCM audio recorder start requested: ${browserAudioDebugInfo()}" }
        runCatching {
            recordingHandle = startBrowserAudioRecording(
                onStarted = {
                    log.info { "Browser PCM audio recorder started" }
                    started.complete(Unit)
                },
                onStopped = { dataUrl: String, pcmByteSize: Int ->
                    val base64 = dataUrl.substringAfter("base64,", "")
                    val pcm = Base64.Default.decode(base64)
                    check(pcm.size == pcmByteSize) {
                        "Browser PCM byte count mismatch: reported=$pcmByteSize decoded=${pcm.size}"
                    }
                    val wav = SpeechPcmWav.encode(pcm)
                    log.info { "Browser PCM audio recorder stopped: pcmBytes=${pcm.size} wavBytes=${wav.size}" }
                    stopped.complete(
                        ClientRecordedAudio(
                            data = wav,
                            format = SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ,
                        )
                    )
                },
                onError = { message: String ->
                    log.warn { "Browser PCM audio recorder error: $message; ${browserAudioDebugInfo()}" }
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
        log.info { "Browser PCM audio recorder stop requested" }
        started.await()
        stopBrowserAudioRecording(recordingHandle)
        return stopped.await()
    }

    override fun cancel() {
        log.info { "Browser PCM audio recorder cancel requested" }
        runCatching { cancelBrowserAudioRecording(recordingHandle) }
        val error = IllegalStateException("Browser audio recording cancelled")
        started.completeExceptionally(error)
        stopped.completeExceptionally(error)
    }
}

@JsFun(
    """
    () => {
        const hasNavigator = typeof navigator !== "undefined";
        const mediaDevices = hasNavigator ? navigator.mediaDevices : undefined;
        const secureContext = typeof window !== "undefined" ? window.isSecureContext : false;
        const protocol = typeof location !== "undefined" ? location.protocol : "";
        const host = typeof location !== "undefined" ? location.host : "";
        return "secureContext=" + secureContext +
            " hasMediaDevices=" + !!mediaDevices +
            " hasGetUserMedia=" + !!(mediaDevices && mediaDevices.getUserMedia) +
            " hasAudioContext=" + (typeof AudioContext !== "undefined") +
            " hasAudioWorkletNode=" + (typeof AudioWorkletNode !== "undefined") +
            " hasOfflineAudioContext=" + (typeof OfflineAudioContext !== "undefined") +
            " protocol=" + protocol +
            " host=" + host;
    }
    """
)
private external fun browserAudioDebugInfo(): String

@JsFun(
    """
    (onStarted, onStopped, onError) => {
        const targetSampleRate = 16000;
        const processorName = "gromozeka-pcm-recorder";
        let stream = null;
        let audioContext = null;
        let sourceNode = null;
        let workletNode = null;
        let silentGain = null;
        let finalized = false;
        const chunks = [];

        const cleanup = async () => {
            try { sourceNode?.disconnect(); } catch (_) {}
            try { workletNode?.disconnect(); } catch (_) {}
            try { silentGain?.disconnect(); } catch (_) {}
            stream?.getTracks().forEach(track => track.stop());
            if (audioContext && audioContext.state !== "closed") {
                await audioContext.close();
            }
        };

        const fail = async error => {
            if (finalized) return;
            finalized = true;
            await cleanup();
            onError(error?.message || String(error));
        };

        const concatenate = parts => {
            const size = parts.reduce((total, part) => total + part.length, 0);
            const samples = new Float32Array(size);
            let offset = 0;
            for (const part of parts) {
                samples.set(part, offset);
                offset += part.length;
            }
            return samples;
        };

        const resample = async (samples, sourceSampleRate) => {
            if (samples.length === 0 || sourceSampleRate === targetSampleRate) {
                return samples;
            }
            const outputLength = Math.max(1, Math.round(samples.length * targetSampleRate / sourceSampleRate));
            const context = new OfflineAudioContext(1, outputLength, targetSampleRate);
            const inputBuffer = context.createBuffer(1, samples.length, sourceSampleRate);
            inputBuffer.copyToChannel(samples, 0);
            const input = context.createBufferSource();
            input.buffer = inputBuffer;
            input.connect(context.destination);
            input.start();
            const rendered = await context.startRendering();
            return new Float32Array(rendered.getChannelData(0));
        };

        const toPcm16LittleEndian = samples => {
            const output = new ArrayBuffer(samples.length * 2);
            const view = new DataView(output);
            for (let index = 0; index < samples.length; index++) {
                const sample = Math.max(-1, Math.min(1, samples[index]));
                const value = sample < 0 ? Math.round(sample * 32768) : Math.round(sample * 32767);
                view.setInt16(index * 2, value, true);
            }
            return output;
        };

        const toDataUrl = buffer => new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onloadend = () => resolve(String(reader.result || ""));
            reader.onerror = () => reject(reader.error || new Error("Failed to read browser PCM audio"));
            reader.readAsDataURL(new Blob([buffer], { type: "application/octet-stream" }));
        });

        const finish = async () => {
            if (finalized) return;
            finalized = true;
            try {
                const sourceSampleRate = audioContext.sampleRate;
                await cleanup();
                const samples = concatenate(chunks);
                const resampled = await resample(samples, sourceSampleRate);
                const pcm = toPcm16LittleEndian(resampled);
                const dataUrl = await toDataUrl(pcm);
                onStopped(dataUrl, pcm.byteLength);
            } catch (error) {
                onError(error?.message || String(error));
            }
        };

        return (async () => {
            try {
                if (!navigator.mediaDevices?.getUserMedia) {
                    throw new Error("Browser microphone API is not available");
                }
                if (typeof AudioContext === "undefined" || typeof AudioWorkletNode === "undefined") {
                    throw new Error("Browser AudioWorklet API is not available");
                }
                if (typeof OfflineAudioContext === "undefined") {
                    throw new Error("Browser offline audio rendering API is not available");
                }

                stream = await navigator.mediaDevices.getUserMedia({
                    audio: {
                        channelCount: 1,
                        echoCancellation: true,
                        noiseSuppression: true,
                        autoGainControl: true
                    }
                });
                audioContext = new AudioContext();

                const workletSource = `
                    class GromozekaPcmRecorderProcessor extends AudioWorkletProcessor {
                        constructor() {
                            super();
                            this.stopped = false;
                            this.pendingChunks = [];
                            this.pendingSamples = 0;
                            this.port.onmessage = event => {
                                if (event.data === "stop") {
                                    this.stopped = true;
                                    this.flush();
                                    this.port.postMessage({ type: "stopped" });
                                }
                            };
                        }

                        flush() {
                            if (this.pendingSamples === 0) return;
                            const samples = new Float32Array(this.pendingSamples);
                            let offset = 0;
                            for (const chunk of this.pendingChunks) {
                                samples.set(chunk, offset);
                                offset += chunk.length;
                            }
                            this.pendingChunks = [];
                            this.pendingSamples = 0;
                            this.port.postMessage({ type: "samples", samples }, [samples.buffer]);
                        }

                        process(inputs) {
                            if (this.stopped) return false;
                            const channels = inputs[0];
                            if (!channels || channels.length === 0 || channels[0].length === 0) return true;

                            const samples = new Float32Array(channels[0].length);
                            if (channels.length === 1) {
                                samples.set(channels[0]);
                            } else {
                                for (const channel of channels) {
                                    for (let index = 0; index < samples.length; index++) {
                                        samples[index] += channel[index] / channels.length;
                                    }
                                }
                            }
                            this.pendingChunks.push(samples);
                            this.pendingSamples += samples.length;
                            if (this.pendingSamples >= 4096) this.flush();
                            return true;
                        }
                    }
                    registerProcessor("gromozeka-pcm-recorder", GromozekaPcmRecorderProcessor);
                `;
                const moduleUrl = URL.createObjectURL(new Blob([workletSource], { type: "text/javascript" }));
                try {
                    await audioContext.audioWorklet.addModule(moduleUrl);
                } finally {
                    URL.revokeObjectURL(moduleUrl);
                }

                sourceNode = audioContext.createMediaStreamSource(stream);
                workletNode = new AudioWorkletNode(audioContext, processorName);
                silentGain = audioContext.createGain();
                silentGain.gain.value = 0;

                workletNode.port.onmessage = event => {
                    if (event.data?.type === "samples") {
                        chunks.push(event.data.samples);
                    } else if (event.data?.type === "stopped") {
                        finish();
                    }
                };
                workletNode.onprocessorerror = () => fail(new Error("Browser PCM audio processor failed"));

                sourceNode.connect(workletNode);
                workletNode.connect(silentGain);
                silentGain.connect(audioContext.destination);
                await audioContext.resume();

                const handle = {
                    state: "recording",
                    stop: () => {
                        if (handle.state !== "recording") return;
                        handle.state = "stopping";
                        workletNode.port.postMessage("stop");
                    },
                    cancel: () => {
                        if (handle.state === "cancelled") return;
                        handle.state = "cancelled";
                        finalized = true;
                        cleanup();
                    }
                };
                onStarted();
                return handle;
            } catch (error) {
                await fail(error);
                return null;
            }
        })();
    }
    """
)
private external fun startBrowserAudioRecording(
    onStarted: () -> Unit,
    onStopped: (String, Int) -> Unit,
    onError: (String) -> Unit,
): Promise<JsAny?>

@JsFun("(handle) => handle?.stop()")
private external fun stopBrowserAudioRecording(handle: JsAny?)

@JsFun("(handle) => handle?.cancel()")
private external fun cancelBrowserAudioRecording(handle: JsAny?)
