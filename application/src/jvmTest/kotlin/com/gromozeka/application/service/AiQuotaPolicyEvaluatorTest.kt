package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.AiAdmissionException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AiQuotaPolicyEvaluatorTest {
    private val evaluator = AiQuotaPolicyEvaluator()
    private val group = AiTpmGroup(AiTpmGroup.Id("group-id"), "account/model", "Work", 1000)
    private val catalog = RuntimeCatalogTemplateApplicationService().createSeed().aiCatalog.copy(tpmGroups = listOf(group))
    private val feedback = AiTpmFeedbackPolicy.Fields(AiQuotaValueSource.Header("Custom-Remaining"))
    private fun policy(selector: AiTpmGroupSelector = AiTpmGroupSelector.RequestValue("/model"), fallback: AiTpmGroup.Id? = null) =
        AiTpmPolicy(selector, AiQuotaCalculator.BuiltIn(), feedback, fallback)

    @Test fun `request value is a human key and fallback is explicit and only selects a group`() = runBlocking {
        val request = Json.parseToJsonElement("""{"model":"account/model","input":"hello"}""")
        assertEquals(group.id, evaluator.prepare(policy(), request, catalog).groupId)
        assertFailsWith<AiAdmissionException> { evaluator.prepare(policy(AiTpmGroupSelector.RequestValue("/missing")), request, catalog) }
        assertEquals(group.id, evaluator.prepare(policy(AiTpmGroupSelector.RequestValue("/missing"), group.id), request, catalog).groupId)
        assertFailsWith<AiAdmissionException> { evaluator.prepare(policy(fallback = group.id).copy(calculator =
            AiQuotaCalculator.BuiltIn(AiQuotaCalculator.Preset.INPUT_PLUS_OUTPUT)), request, catalog) }
        assertEquals("account/model", group.key)
    }

    @Test fun `header names ignore case preserve debt and reject ambiguous or malformed feedback`() = runBlocking {
        val observed = evaluator.feedback(feedback, AiHttpQuotaFeedback(200, mapOf("CUSTOM-REMAINING" to listOf("-27"))), group)
        assertEquals(-27.0, observed.remaining)
        assertFailsWith<AiAdmissionException> { evaluator.feedback(feedback, AiHttpQuotaFeedback(200, emptyMap()), group) }
        assertFailsWith<IllegalArgumentException> { evaluator.feedback(feedback, AiHttpQuotaFeedback(200, mapOf("custom-remaining" to listOf("NaN"))), group) }
        assertFailsWith<IllegalArgumentException> { evaluator.feedback(feedback, AiHttpQuotaFeedback(200, mapOf("custom-remaining" to listOf("1", "2"))), group) }
        assertEquals(10000, evaluator.feedback(feedback, AiHttpQuotaFeedback(429, emptyMap()), group).retryAfterMillis)
    }

    @Test fun `JSON Pointer works in body and JSON encoded header with explicit retry units`() = runBlocking {
        val policy = AiTpmFeedbackPolicy.Fields(AiQuotaValueSource.Header("quota", "/remaining"), AiQuotaValueSource.Body("/retry"), AiTpmFeedbackPolicy.RetryFormat.MILLISECONDS)
        val result = evaluator.feedback(policy, AiHttpQuotaFeedback(200, mapOf("quota" to listOf("""{"remaining":-5}""")),
            Json.parseToJsonElement("""{"retry":1.2}""")), group)
        assertEquals(AiTpmFeedback(-5.0, 2), result)
    }

    @Test fun `Retry After supports seconds or server relative HTTP date without treating reset as cooldown`() = runBlocking {
        val policy = AiTpmFeedbackPolicy.Fields(AiQuotaValueSource.Header("quota"), AiQuotaValueSource.Header("retry-after"),
            AiTpmFeedbackPolicy.RetryFormat.RETRY_AFTER)
        val headers = mapOf("Date" to listOf("Wed, 11 Nov 2026 00:00:00 GMT"), "Retry-After" to listOf("Wed, 11 Nov 2026 00:00:03 GMT"))
        assertEquals(3000, evaluator.feedback(policy, AiHttpQuotaFeedback(429, headers), group).retryAfterMillis)
        assertEquals(2000, evaluator.feedback(policy, AiHttpQuotaFeedback(429, headers + ("Retry-After" to listOf("2"))), group).retryAfterMillis)
    }

    @Test fun `presets are explicit estimates and do not invent output reserves`() {
        val request = Json.parseToJsonElement("""{"model":"model","input":"abcdefgh","max_output_tokens":10}""")
        assertEquals(2, evaluator.estimate(AiQuotaCalculator.BuiltIn(AiQuotaCalculator.Preset.INPUT_ONLY), request))
        assertEquals(12, evaluator.estimate(AiQuotaCalculator.BuiltIn(AiQuotaCalculator.Preset.INPUT_PLUS_OUTPUT), request))
        assertEquals(10, evaluator.estimate(AiQuotaCalculator.BuiltIn(AiQuotaCalculator.Preset.OPENAI_MAXIMUM), request))
        val unicode = Json.parseToJsonElement("""{"input":"😀😀😀😀"}""")
        assertEquals(1, evaluator.estimate(AiQuotaCalculator.BuiltIn(AiQuotaCalculator.Preset.INPUT_ONLY), unicode))
        assertFailsWith<AiAdmissionException> { evaluator.estimate(AiQuotaCalculator.BuiltIn(AiQuotaCalculator.Preset.INPUT_PLUS_OUTPUT), unicode) }
    }

    @Test fun `commands use stdin without shell interpolation and failures never expose output`() = runBlocking {
        if (System.getProperty("os.name").startsWith("Windows")) return@runBlocking
        val request = Json.parseToJsonElement("""{"input":"$(echo private-marker); ' quoted"}""")
        val echoed = evaluator.runCommand(AiQuotaCommand("/bin/sh", listOf("-c", "cat")), request)
        assertEquals(request, Json.parseToJsonElement(echoed))
        val error = assertFailsWith<IllegalArgumentException> {
            evaluator.runCommand(AiQuotaCommand("/bin/sh", listOf("-c", "cat >/dev/null; printf 'private-marker' >&2; exit 3")), request)
        }
        assertFalse(error.message.orEmpty().contains("private-marker"))
        assertFailsWith<AiAdmissionException> {
            evaluator.runCommand(AiQuotaCommand("/bin/sh", listOf("-c", "sleep 30"), timeoutSeconds = 1), request)
        }
        assertEquals(group.id, evaluator.prepare(policy(AiTpmGroupSelector.Command(AiQuotaCommand("/bin/sh", listOf("-c", "exit 2"))), group.id), request, catalog).groupId)
        val largeInput = JsonPrimitive("x".repeat(1_000_000))
        assertFailsWith<java.io.IOException> {
            evaluator.runCommand(AiQuotaCommand("/bin/sh", listOf("-c", "exec 0<&-; sleep 10; printf 100")), largeInput)
        }
    }
}
