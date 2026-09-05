package com.gromozeka.worker.runtime

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class WorkerSoundVolumeOverrideTest {
    private var snapshot: String? = null
    private var volume = 2
    private var writesFail = false
    private var volumeFails = false
    private fun override() = WorkerSoundVolumeOverride(
        readSnapshot = { snapshot },
        writeSnapshot = { if (writesFail) error("Disk full") else snapshot = it },
        currentVolume = { volume },
        setVolume = { if (volumeFails) error("Volume denied") else volume = it },
    )

    @Test
    fun `volume override persists before mutation and recovers after recreation`() = runTest {
        override().boost(7)
        assertEquals(7, volume)
        assertNotNull(snapshot)
        override().restore()
        assertEquals(2, volume)
        override().restore()
        assertEquals(2, volume)
    }

    @Test
    fun `manual volume change is preserved`() = runTest {
        override().boost(7)
        volume = 3
        override().restore()
        assertEquals(3, volume)
        override().boost(7)
        override().restore()
        assertEquals(3, volume)
    }

    @Test
    fun `persistence failure prevents boost and failed restoration retains recovery state`() = runTest {
        writesFail = true
        assertFailsWith<IllegalStateException> { override().boost(7) }
        assertEquals(2, volume)
        writesFail = false
        override().boost(7)
        volumeFails = true
        assertFailsWith<IllegalStateException> { override().restore() }
        volumeFails = false
        override().restore()
        assertEquals(2, volume)
    }

    @Test
    fun `corrupt snapshots fail closed without changing volume`() = runTest {
        snapshot = "corrupt"
        assertFailsWith<IllegalArgumentException> { override().boost(7) }
        snapshot = "{\"previous\":-1,\"applied\":7}"
        assertFailsWith<IllegalArgumentException> { override().boost(7) }
        assertEquals(2, volume)
    }

    @Test
    fun `uncommitted snapshot prevents volume mutation`() = runTest {
        val override = WorkerSoundVolumeOverride({ null }, {}, { volume }, { volume = it })
        assertFailsWith<IllegalStateException> { override.boost(7) }
        assertEquals(2, volume)
    }

    @Test
    fun `DND or changed routing preserves pending restoration until volume is inspectable`() = runTest {
        override().boost(7)
        val pending = snapshot
        val unavailable = WorkerSoundVolumeOverride({ snapshot }, { snapshot = it }, { null }, { volume = it })
        unavailable.restore()
        assertEquals(pending, snapshot)
        assertEquals(7, volume)
        assertFailsWith<IllegalArgumentException> { unavailable.boost(7) }
        override().restore()
        assertEquals(2, volume)
    }
}
