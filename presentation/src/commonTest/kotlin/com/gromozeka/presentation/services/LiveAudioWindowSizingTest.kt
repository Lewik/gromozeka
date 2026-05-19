package com.gromozeka.presentation.services

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveAudioWindowSizingTest {
    @Test
    fun keepsDefaultWindowWhenRecognizerKeepsUp() {
        assertEquals(
            20_000,
            LiveAudioWindowSizing.backlogPreservingWindowBytes(
                pendingRawBytes = 10_000,
                defaultWindowBytes = 20_000,
                overlapBytes = 5_000,
            )
        )
    }

    @Test
    fun expandsWindowToCoverBacklogPlusOverlap() {
        assertEquals(
            45_000,
            LiveAudioWindowSizing.backlogPreservingWindowBytes(
                pendingRawBytes = 40_000,
                defaultWindowBytes = 20_000,
                overlapBytes = 5_000,
            )
        )
    }

    @Test
    fun keepsFullBacklogInsteadOfCappingAtMaximumAdaptiveWindow() {
        assertEquals(
            125_000,
            LiveAudioWindowSizing.backlogPreservingWindowBytes(
                pendingRawBytes = 120_000,
                defaultWindowBytes = 20_000,
                overlapBytes = 5_000,
            )
        )
    }
}
