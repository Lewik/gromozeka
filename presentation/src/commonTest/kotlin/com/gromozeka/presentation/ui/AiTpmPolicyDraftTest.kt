package com.gromozeka.presentation.ui

import com.gromozeka.domain.model.ai.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class AiTpmPolicyDraftTest {
    @Test fun disabledByDefaultDoesNotValidateInactiveFields() {
        assertNull(AiTpmPolicyDraft(null).build())
    }

    @Test fun selectorsCalculatorsAndFeedbackRoundTripWithoutLosingEmptyRootPointer() {
        val command = AiQuotaCommand("python3", listOf("quota.py"), 20)
        val selectors = listOf(AiTpmGroupSelector.Fixed(AiTpmGroup.Id("quota")), AiTpmGroupSelector.RequestValue("/model"), AiTpmGroupSelector.Command(command))
        val calculators = listOf(AiQuotaCalculator.BuiltIn(AiQuotaCalculator.Preset.INPUT_PLUS_OUTPUT, 3.0, 1.2, 17, 500), AiQuotaCalculator.Command(command))
        val feedback = listOf(AiTpmFeedbackPolicy.Command(command), AiTpmFeedbackPolicy.Fields(
            AiQuotaValueSource.Header("custom", ""), AiQuotaValueSource.Body("/wait"), AiTpmFeedbackPolicy.RetryFormat.RETRY_AFTER))
        for (selector in selectors) for (calculator in calculators) for (reader in feedback) {
            val policy = AiTpmPolicy(selector, calculator, reader, AiTpmGroup.Id("fallback"))
            assertEquals(policy, AiTpmPolicyDraft(policy).build())
            assertEquals(policy, Json.decodeFromString<AiTpmPolicy>(Json.encodeToString(AiTpmPolicy.serializer(), policy)))
        }
    }

    @Test fun enabledPolicyRequiresAGroupAndFeedbackAndRejectsInvalidPointers() {
        val draft = AiTpmPolicyDraft(null).apply { enabled = true }
        assertFailsWith<IllegalArgumentException> { draft.build() }
        draft.groupId = AiTpmGroup.Id("quota")
        assertFailsWith<IllegalArgumentException> { draft.build() }
        draft.remaining.header = "custom-remaining"
        assertNotNull(draft.build())
        draft.groupMode = "request"
        draft.pointer = "/bad~2escape"
        assertFailsWith<IllegalArgumentException> { draft.build() }
    }
}
