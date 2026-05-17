package com.gromozeka.infrastructure.ai.speech

import kotlin.math.floor
import kotlin.math.roundToInt

internal object WhisperWavNormalizer {
    private const val TARGET_SAMPLE_RATE = 16_000
    private const val TARGET_CHANNELS = 1
    private const val TARGET_BITS_PER_SAMPLE = 16
    private const val PCM_FORMAT = 1

    fun normalize(
        audioData: ByteArray,
        mediaType: String,
        fileExtension: String,
    ): ByteArray {
        require(isWav(mediaType, fileExtension)) {
            "Local Whisper supports only WAV PCM input. " +
                "Received mediaType=$mediaType fileExtension=$fileExtension"
        }

        val wav = parseWav(audioData)
        require(wav.audioFormat == PCM_FORMAT) {
            "Local Whisper supports only uncompressed PCM WAV input. WAV format=${wav.audioFormat}"
        }
        require(wav.bitsPerSample == TARGET_BITS_PER_SAMPLE) {
            "Local Whisper supports only 16-bit PCM WAV input. WAV bitsPerSample=${wav.bitsPerSample}"
        }
        require(wav.channels > 0) {
            "Local Whisper WAV input must have at least one channel"
        }
        require(wav.sampleRate > 0) {
            "Local Whisper WAV input sample rate must be positive"
        }

        val monoSamples = wav.toMonoSamples()
        val normalizedSamples = if (wav.sampleRate == TARGET_SAMPLE_RATE) {
            monoSamples
        } else {
            monoSamples.resample(wav.sampleRate, TARGET_SAMPLE_RATE)
        }

        return writeWav(normalizedSamples)
    }

    private fun isWav(mediaType: String, fileExtension: String): Boolean =
        mediaType.contains("wav", ignoreCase = true) ||
            fileExtension.trim().trimStart('.').equals("wav", ignoreCase = true)

    private fun parseWav(audioData: ByteArray): WavInput {
        require(audioData.size >= 44) { "WAV input is too small: ${audioData.size} bytes" }
        require(audioData.ascii(0, 4) == "RIFF") { "WAV input must start with RIFF" }
        require(audioData.ascii(8, 4) == "WAVE") { "WAV input must contain WAVE header" }

        var offset = 12
        var format: WavFormat? = null
        var dataOffset = -1
        var dataSize = 0

        while (offset + 8 <= audioData.size) {
            val chunkId = audioData.ascii(offset, 4)
            val chunkSize = audioData.readLittleEndianUInt(offset + 4).toInt()
            val chunkDataOffset = offset + 8
            val nextOffset = chunkDataOffset + chunkSize + (chunkSize and 1)

            require(chunkSize >= 0 && chunkDataOffset + chunkSize <= audioData.size) {
                "Invalid WAV chunk '$chunkId' size=$chunkSize at offset=$offset"
            }

            when (chunkId) {
                "fmt " -> {
                    require(chunkSize >= 16) { "WAV fmt chunk is too small: $chunkSize bytes" }
                    format = WavFormat(
                        audioFormat = audioData.readLittleEndianShort(chunkDataOffset),
                        channels = audioData.readLittleEndianShort(chunkDataOffset + 2),
                        sampleRate = audioData.readLittleEndianInt(chunkDataOffset + 4),
                        blockAlign = audioData.readLittleEndianShort(chunkDataOffset + 12),
                        bitsPerSample = audioData.readLittleEndianShort(chunkDataOffset + 14),
                    )
                }

                "data" -> {
                    dataOffset = chunkDataOffset
                    dataSize = chunkSize
                }
            }

            offset = nextOffset
        }

        val wavFormat = requireNotNull(format) { "WAV input is missing fmt chunk" }
        require(dataOffset >= 0) { "WAV input is missing data chunk" }
        require(wavFormat.blockAlign > 0) { "WAV block align must be positive" }

        return WavInput(
            audioFormat = wavFormat.audioFormat,
            channels = wavFormat.channels,
            sampleRate = wavFormat.sampleRate,
            blockAlign = wavFormat.blockAlign,
            bitsPerSample = wavFormat.bitsPerSample,
            data = audioData.copyOfRange(dataOffset, dataOffset + dataSize),
        )
    }

    private fun WavInput.toMonoSamples(): ShortArray {
        val sampleBytes = bitsPerSample / 8
        val frameCount = data.size / blockAlign
        val samples = ShortArray(frameCount)

        for (frameIndex in 0 until frameCount) {
            val frameOffset = frameIndex * blockAlign
            var mixed = 0
            for (channelIndex in 0 until channels) {
                val sampleOffset = frameOffset + channelIndex * sampleBytes
                mixed += data.readLittleEndianShort(sampleOffset).toShort().toInt()
            }
            samples[frameIndex] = (mixed / channels).toShort()
        }

        return samples
    }

    private fun ShortArray.resample(sourceRate: Int, targetRate: Int): ShortArray {
        if (isEmpty()) return this
        val targetSize = (size * targetRate.toDouble() / sourceRate).roundToInt().coerceAtLeast(1)
        val output = ShortArray(targetSize)

        for (targetIndex in output.indices) {
            val sourcePosition = targetIndex * sourceRate.toDouble() / targetRate
            val leftIndex = floor(sourcePosition).toInt().coerceIn(indices)
            val rightIndex = (leftIndex + 1).coerceAtMost(lastIndex)
            val fraction = sourcePosition - leftIndex
            val sample = this[leftIndex] * (1.0 - fraction) + this[rightIndex] * fraction
            output[targetIndex] = sample.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        return output
    }

    private fun writeWav(samples: ShortArray): ByteArray {
        val dataSize = samples.size * 2
        val output = ByteArray(44 + dataSize)
        output.writeAscii(0, "RIFF")
        output.writeLittleEndianInt(4, 36 + dataSize)
        output.writeAscii(8, "WAVE")
        output.writeAscii(12, "fmt ")
        output.writeLittleEndianInt(16, 16)
        output.writeLittleEndianShort(20, PCM_FORMAT)
        output.writeLittleEndianShort(22, TARGET_CHANNELS)
        output.writeLittleEndianInt(24, TARGET_SAMPLE_RATE)
        output.writeLittleEndianInt(28, TARGET_SAMPLE_RATE * TARGET_CHANNELS * TARGET_BITS_PER_SAMPLE / 8)
        output.writeLittleEndianShort(32, TARGET_CHANNELS * TARGET_BITS_PER_SAMPLE / 8)
        output.writeLittleEndianShort(34, TARGET_BITS_PER_SAMPLE)
        output.writeAscii(36, "data")
        output.writeLittleEndianInt(40, dataSize)

        var offset = 44
        for (sample in samples) {
            output.writeLittleEndianShort(offset, sample.toInt())
            offset += 2
        }

        return output
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        copyOfRange(offset, offset + length).decodeToString()

    private fun ByteArray.readLittleEndianUInt(offset: Int): Long =
        readLittleEndianInt(offset).toLong() and 0xffffffffL

    private fun ByteArray.readLittleEndianInt(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private fun ByteArray.readLittleEndianShort(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.writeAscii(offset: Int, value: String) {
        value.encodeToByteArray().copyInto(this, destinationOffset = offset)
    }

    private fun ByteArray.writeLittleEndianInt(offset: Int, value: Int) {
        this[offset] = (value and 0xff).toByte()
        this[offset + 1] = ((value ushr 8) and 0xff).toByte()
        this[offset + 2] = ((value ushr 16) and 0xff).toByte()
        this[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun ByteArray.writeLittleEndianShort(offset: Int, value: Int) {
        this[offset] = (value and 0xff).toByte()
        this[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private data class WavFormat(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val blockAlign: Int,
        val bitsPerSample: Int,
    )

    private data class WavInput(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val blockAlign: Int,
        val bitsPerSample: Int,
        val data: ByteArray,
    )
}
