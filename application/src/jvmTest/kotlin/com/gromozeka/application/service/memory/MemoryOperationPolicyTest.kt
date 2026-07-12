package com.gromozeka.application.service.memory

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryOperationPolicyTest {
    @Test
    fun documentSettingAppliesOnlyWhenToolDoesNotOverrideIt() {
        assertTrue(
            resolveMemoryForceWrite(
                explicitForceWrite = null,
                documentInput = true,
                forceDocumentsByDefault = true,
            )
        )
        assertFalse(
            resolveMemoryForceWrite(
                explicitForceWrite = false,
                documentInput = true,
                forceDocumentsByDefault = true,
            )
        )
        assertTrue(
            resolveMemoryForceWrite(
                explicitForceWrite = true,
                documentInput = true,
                forceDocumentsByDefault = false,
            )
        )
    }

    @Test
    fun documentSettingDoesNotForceOrdinaryText() {
        assertFalse(
            resolveMemoryForceWrite(
                explicitForceWrite = null,
                documentInput = false,
                forceDocumentsByDefault = true,
            )
        )
        assertTrue(
            resolveMemoryForceWrite(
                explicitForceWrite = true,
                documentInput = false,
                forceDocumentsByDefault = false,
            )
        )
    }
}
