package com.gromozeka.presentation.services

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveAudioWindowSizingTest {
    @Test
    fun keepsDefaultWindowWhenRecognizerKeepsUp() {
        assertEquals(
            20_000,
            LiveAudioWindowSizing.adaptiveWindowBytes(
                pendingRawBytes = 10_000,
                defaultWindowBytes = 20_000,
                overlapBytes = 5_000,
                maxWindowBytes = 90_000,
            )
        )
    }

    @Test
    fun expandsWindowToCoverBacklogPlusOverlap() {
        assertEquals(
            45_000,
            LiveAudioWindowSizing.adaptiveWindowBytes(
                pendingRawBytes = 40_000,
                defaultWindowBytes = 20_000,
                overlapBytes = 5_000,
                maxWindowBytes = 90_000,
            )
        )
    }

    @Test
    fun capsWindowAtMaximumAdaptiveWindow() {
        assertEquals(
            90_000,
            LiveAudioWindowSizing.adaptiveWindowBytes(
                pendingRawBytes = 120_000,
                defaultWindowBytes = 20_000,
                overlapBytes = 5_000,
                maxWindowBytes = 90_000,
            )
        )
    }
}
