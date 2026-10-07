package com.gromozeka.domain.visual

import kotlin.test.*

class VisualOutputFramerTest {
    @Test fun waitsForCompleteLinesAndTracksByteOffsets() {
        val framer = VisualOutputFramer()
        val text = "{\"data\":{\"text\":\"שלום😀\"}}"
        val bytes = (text + "\r\n").encodeToByteArray()
        assertTrue(framer.append(bytes.copyOfRange(0, bytes.size - 1)).isEmpty())
        val record = framer.append(bytes.copyOfRange(bytes.size - 1, bytes.size)).single()
        assertEquals(text, record.json)
        assertEquals(bytes.size.toLong(), record.endByte)
    }

    @Test fun terminalEofCanCompleteTheFinalRecord() {
        val framer = VisualOutputFramer()
        assertTrue(framer.append("{}".encodeToByteArray()).isEmpty())
        assertEquals(VisualOutputRecord(2, "{}"), framer.append(byteArrayOf(), finished = true).single())
        assertTrue(framer.append(byteArrayOf(), finished = true).isEmpty())
    }

    @Test fun oversizedLineIsDiscardedUntilItsDelimiterAndReaderRecovers() {
        val framer = VisualOutputFramer()
        assertTrue(framer.append(ByteArray(20_000) { 'x'.code.toByte() }).isEmpty())
        val records = framer.append("\n{}\n".encodeToByteArray())
        assertEquals(2, records.size)
        assertNotNull(records[0].error)
        assertEquals(20_001L, records[0].endByte)
        assertEquals("{}", records[1].json)
        assertEquals(20_004L, records[1].endByte)
    }

    @Test fun exactLimitAllowsCrLfAndInvalidUtf8IsReported() {
        val framer = VisualOutputFramer()
        val atLimit = "x".repeat(VisualLimits.OUTPUT_LINE_BYTES)
        assertEquals(atLimit, framer.append((atLimit + "\r\n").encodeToByteArray()).single().json)
        val invalid = framer.append(byteArrayOf(0xC3.toByte(), '\n'.code.toByte())).single()
        assertNotNull(invalid.error)
        assertEquals("{}", framer.append("\n".encodeToByteArray()).single().json)
    }
}
