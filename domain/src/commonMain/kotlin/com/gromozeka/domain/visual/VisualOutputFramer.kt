package com.gromozeka.domain.visual

/** Bounded NDJSON framing over byte chunks. A running process's partial line is never emitted. */
class VisualOutputFramer(initialOffset: Long = 0) {
    private val buffer = ByteArray(VisualLimits.OUTPUT_LINE_BYTES + 1) // Extra byte permits CRLF.
    private var length = 0
    private var oversized = false
    private var offset = initialOffset

    fun append(bytes: ByteArray, finished: Boolean = false): List<VisualOutputRecord> = buildList {
        for (byte in bytes) {
            offset++
            if (byte == '\n'.code.toByte()) {
                add(record())
            } else if (!oversized) {
                if (length < buffer.size) buffer[length++] = byte else oversized = true
            }
        }
        if (finished && (length > 0 || oversized)) add(record())
    }

    private fun record(): VisualOutputRecord {
        val size = if (length > 0 && buffer[length - 1] == '\r'.code.toByte()) length - 1 else length
        val result = if (oversized || size > VisualLimits.OUTPUT_LINE_BYTES) {
            VisualOutputRecord(offset, error = "Output line exceeds ${VisualLimits.OUTPUT_LINE_BYTES} UTF-8 bytes")
        } else {
            try {
                val text = buffer.copyOf(size).decodeToString(throwOnInvalidSequence = true)
                VisualOutputRecord(offset, json = if (text.isBlank()) "{}" else text)
            } catch (_: CharacterCodingException) {
                VisualOutputRecord(offset, error = "Output line is not valid UTF-8")
            }
        }
        length = 0
        oversized = false
        return result
    }
}
