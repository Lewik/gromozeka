package com.gromozeka.presentation.services

import klog.KLoggers
import kotlin.JsFun
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalEncodingApi::class)
class BrowserClientAudioPlayer : ClientAudioPlayer {
    private companion object {
        const val PCM_START_PREBUFFER_MS = 400
    }

    private val log = KLoggers.logger(this)
    private val manager = createBrowserAudioPlayerManager()
    private var activePlayback: JsAny? = null

    override suspend fun playAudio(data: ByteArray, mediaType: String, fileExtension: String) {
        if (data.isEmpty()) return

        val playback = beginBrowserAudioPlayback(manager).await()
        activePlayback = playback
        log.info {
            "Browser encoded audio playback started: bytes=${data.size} mediaType=$mediaType " +
                "fileExtension=$fileExtension audioContextState=${browserAudioContextState(manager)}"
        }
        try {
            appendBrowserEncodedAudio(playback, Base64.Default.encode(data)).await()
            finishBrowserAudioPlayback(playback).await()
            log.info { "Browser encoded audio playback completed" }
        } finally {
            stopBrowserAudioPlayback(playback)
            if (activePlayback === playback) {
                activePlayback = null
            }
        }
    }

    override suspend fun playPcmStream(
        chunks: Flow<ByteArray>,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ) {
        require(sampleRate > 0) { "PCM sample rate must be positive" }
        require(channels > 0) { "PCM channel count must be positive" }
        require(bitsPerSample == 16) { "Browser playback supports only signed 16-bit PCM" }

        val frameSizeBytes = channels * bitsPerSample / 8
        val frameBuffer = PcmFrameBuffer(
            frameSizeBytes = frameSizeBytes,
            startPrebufferBytes = PcmFrameBuffer.prebufferBytes(
                sampleRate = sampleRate,
                frameSizeBytes = frameSizeBytes,
                prebufferMs = PCM_START_PREBUFFER_MS,
            ),
        )
        val playback = beginBrowserAudioPlayback(manager).await()
        activePlayback = playback
        log.info {
            "Browser PCM playback started: sampleRate=$sampleRate channels=$channels " +
                "bitsPerSample=$bitsPerSample audioContextState=${browserAudioContextState(manager)}"
        }

        try {
            chunks.collect { chunk ->
                frameBuffer.push(chunk)?.let { audioBytes ->
                    appendBrowserPcmAudio(
                        playback = playback,
                        base64Data = Base64.Default.encode(audioBytes),
                        sampleRate = sampleRate,
                        channels = channels,
                    )
                }
            }
            frameBuffer.finish()?.let { audioBytes ->
                appendBrowserPcmAudio(
                    playback = playback,
                    base64Data = Base64.Default.encode(audioBytes),
                    sampleRate = sampleRate,
                    channels = channels,
                )
            }
            finishBrowserAudioPlayback(playback).await()
            log.info { "Browser PCM playback completed" }
        } finally {
            stopBrowserAudioPlayback(playback)
            if (activePlayback === playback) {
                activePlayback = null
            }
        }
    }

    override fun stop() {
        activePlayback = null
        stopBrowserAudioPlayer(manager)
    }
}

@JsFun(
    """
    () => {
        const AudioContextConstructor = globalThis.AudioContext || globalThis.webkitAudioContext;
        if (!AudioContextConstructor) {
            throw new Error("Browser Web Audio API is not available");
        }

        let context = null;
        let activePlayback = null;

        const ensureContext = async () => {
            if (!context || context.state === "closed") {
                context = new AudioContextConstructor();
            }
            if (context.state !== "running") {
                try {
                    await context.resume();
                } catch (_) {
                }
            }
            return context;
        };

        const unlock = () => {
            ensureContext()
                .then(audioContext => {
                    if (audioContext.state !== "running") return;
                    const source = audioContext.createBufferSource();
                    source.buffer = audioContext.createBuffer(1, 1, audioContext.sampleRate);
                    source.connect(audioContext.destination);
                    source.start();
                })
                .catch(() => {});
        };

        globalThis.addEventListener("pointerdown", unlock, { capture: true, passive: true });
        globalThis.addEventListener("touchend", unlock, { capture: true, passive: true });
        globalThis.addEventListener("keydown", unlock, { capture: true });

        const createPlayback = audioContext => {
            let sources = new Set();
            let nextStartTime = 0;
            let finishing = false;
            let settled = false;
            let resolveCompletion;
            let rejectCompletion;
            const completion = new Promise((resolve, reject) => {
                resolveCompletion = resolve;
                rejectCompletion = reject;
            });

            const settle = error => {
                if (settled) return;
                settled = true;
                if (activePlayback === playback) {
                    activePlayback = null;
                }
                if (error) {
                    rejectCompletion(error);
                } else {
                    resolveCompletion();
                }
            };

            const schedule = buffer => {
                if (settled || buffer.length === 0) return;
                const source = audioContext.createBufferSource();
                source.buffer = buffer;
                source.connect(audioContext.destination);
                sources.add(source);
                source.onended = () => {
                    sources.delete(source);
                    try {
                        source.disconnect();
                    } catch (_) {
                    }
                    if (finishing && sources.size === 0) {
                        settle();
                    }
                };

                const startTime = Math.max(
                    nextStartTime,
                    audioContext.currentTime + (nextStartTime === 0 ? 0.05 : 0.01)
                );
                source.start(startTime);
                nextStartTime = startTime + buffer.duration;
            };

            const playback = {
                context: audioContext,
                schedule,
                finish: () => {
                    finishing = true;
                    if (sources.size === 0) {
                        settle();
                    }
                    return completion;
                },
                stop: () => {
                    if (settled) return;
                    for (const source of sources) {
                        try {
                            source.stop();
                        } catch (_) {
                        }
                        try {
                            source.disconnect();
                        } catch (_) {
                        }
                    }
                    sources.clear();
                    settle();
                },
                fail: error => {
                    for (const source of sources) {
                        try {
                            source.stop();
                        } catch (_) {
                        }
                    }
                    sources.clear();
                    settle(error);
                }
            };
            return playback;
        };

        return {
            ensureContext,
            get contextState() {
                return context?.state || "not-created";
            },
            async begin() {
                activePlayback?.stop();
                const audioContext = await ensureContext();
                const playback = createPlayback(audioContext);
                activePlayback = playback;
                return playback;
            },
            stop() {
                activePlayback?.stop();
                activePlayback = null;
            }
        };
    }
    """
)
private external fun createBrowserAudioPlayerManager(): JsAny

@JsFun("(manager) => manager.begin()")
private external fun beginBrowserAudioPlayback(manager: JsAny): Promise<JsAny>

@JsFun(
    """
    (playback, base64Data, sampleRate, channels) => {
        const binary = atob(base64Data);
        const bytes = new Uint8Array(binary.length);
        for (let index = 0; index < binary.length; index++) {
            bytes[index] = binary.charCodeAt(index);
        }
        const frameSizeBytes = channels * 2;
        if (bytes.byteLength % frameSizeBytes !== 0) {
            throw new Error(
                "PCM byte count " + bytes.byteLength + " is not aligned to frame size " + frameSizeBytes
            );
        }

        const frameCount = bytes.byteLength / frameSizeBytes;
        if (frameCount === 0) return;
        const audioBuffer = playback.context.createBuffer(channels, frameCount, sampleRate);
        const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
        for (let channel = 0; channel < channels; channel++) {
            const samples = audioBuffer.getChannelData(channel);
            for (let frame = 0; frame < frameCount; frame++) {
                const sampleOffset = (frame * channels + channel) * 2;
                samples[frame] = view.getInt16(sampleOffset, true) / 32768;
            }
        }
        playback.schedule(audioBuffer);
    }
    """
)
private external fun appendBrowserPcmAudio(
    playback: JsAny,
    base64Data: String,
    sampleRate: Int,
    channels: Int,
)

@JsFun(
    """
    (playback, base64Data) => {
        const binary = atob(base64Data);
        const bytes = new Uint8Array(binary.length);
        for (let index = 0; index < binary.length; index++) {
            bytes[index] = binary.charCodeAt(index);
        }
        return playback.context.decodeAudioData(bytes.buffer)
            .then(buffer => playback.schedule(buffer))
            .catch(error => {
                playback.fail(error);
                throw error;
            });
    }
    """
)
private external fun appendBrowserEncodedAudio(playback: JsAny, base64Data: String): Promise<JsAny?>

@JsFun("(playback) => playback.finish()")
private external fun finishBrowserAudioPlayback(playback: JsAny): Promise<JsAny?>

@JsFun("(playback) => playback?.stop()")
private external fun stopBrowserAudioPlayback(playback: JsAny?)

@JsFun("(manager) => manager.stop()")
private external fun stopBrowserAudioPlayer(manager: JsAny)

@JsFun("(manager) => manager.contextState")
private external fun browserAudioContextState(manager: JsAny): String
