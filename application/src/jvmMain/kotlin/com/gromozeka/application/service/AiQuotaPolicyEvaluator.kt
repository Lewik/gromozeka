package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.AiAdmissionException
import com.gromozeka.shared.json.JsonPointer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import org.springframework.stereotype.Service
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

@Service
class AiQuotaPolicyEvaluator {
    private val commands = Semaphore(4)

    suspend fun prepare(policy: AiTpmPolicy, request: JsonElement, catalog: AiCatalog): AiTpmReservation {
        val group = try {
            when (val selector = policy.group) {
                is AiTpmGroupSelector.Fixed -> requireNotNull(catalog.tpmGroups.find { it.id == selector.groupId }) { "TPM group is unavailable" }
                is AiTpmGroupSelector.RequestValue -> findKey(catalog, requireNotNull(JsonPointer(selector.pointer).resolve(request) as? JsonPrimitive) {
                    "TPM group selector did not resolve to a string"
                }.also { require(it.isString) { "TPM group selector must return a string" } }.content)
                is AiTpmGroupSelector.Command -> findKey(catalog, runCommand(selector.command, request).trimEnd('\r', '\n'))
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val fallback = policy.fallbackGroupId ?: throw AiAdmissionException("TPM group selection failed (${error::class.simpleName})")
            requireNotNull(catalog.tpmGroups.find { it.id == fallback }) { "TPM fallback group is unavailable" }
        }
        val units = when (val calculator = policy.calculator) {
            is AiQuotaCalculator.Command -> runCommand(calculator.command, request).trim().toLongOrNull()
                ?: throw AiAdmissionException("Quota command must return one integer")
            is AiQuotaCalculator.BuiltIn -> estimate(calculator, request)
        }
        return AiTpmReservation(group.id, units, policy.feedback)
    }

    suspend fun feedback(policy: AiTpmFeedbackPolicy, response: AiHttpQuotaFeedback, group: AiTpmGroup): AiTpmFeedback {
        val required = response.status in 200..299
        val result = when (policy) {
            is AiTpmFeedbackPolicy.Fields -> {
                val remaining = extract(policy.remaining, response)?.let { scalar ->
                    scalar.toDoubleOrNull() ?: throw AiAdmissionException("TPM remaining quota is not numeric")
                }
                val delay = policy.retryDelay?.let { extract(it, response) }?.let { parseDelay(it, policy.retryFormat, response) }
                AiTpmFeedback(remaining, delay)
            }
            is AiTpmFeedbackPolicy.Command -> {
                val result = runCommand(policy.command, Json.encodeToJsonElement(AiHttpQuotaFeedback.serializer(), response))
                Json.decodeFromString<AiTpmFeedback>(result)
            }
        }
        if (required && result.remaining == null) throw AiAdmissionException("Successful AI response has no required TPM feedback")
        return if (response.status == 429 && result.retryAfterMillis == null)
            result.copy(retryAfterMillis = group.missingRetryDelaySeconds * 1000L) else result
    }

    private fun findKey(catalog: AiCatalog, key: String): AiTpmGroup {
        requireAiQuotaKey(key)
        return requireNotNull(catalog.tpmGroups.find { it.key == key }) { "TPM key has no configured group" }
    }

    internal fun estimate(calculator: AiQuotaCalculator.BuiltIn, request: JsonElement): Long {
        fun count(element: JsonElement): Long = when (element) {
            is JsonPrimitive -> if (element.isString) element.content.codePointCount(0, element.content.length).toLong() else 0
            is JsonArray -> element.sumOf(::count)
            is JsonObject -> element.values.sumOf(::count)
        }
        val chars = if (calculator.preset == AiQuotaCalculator.Preset.CHARACTERS)
            request.toString().let { it.codePointCount(0, it.length).toLong() }
        else {
            val body = requireNotNull(request as? JsonObject) { "Quota input must be a JSON object" }
            require(listOf("messages", "input", "prompt").any { it in body }) { "Request has no supported text input" }
            listOf("messages", "input", "prompt", "instructions", "system", "tools", "response_format", "text")
                .sumOf { body[it]?.let(::count) ?: 0 }
        }
        val estimate = ceil(chars / calculator.charactersPerUnit * calculator.multiplier) + calculator.fixedOverhead
        require(estimate.isFinite() && estimate <= 1_000_000_000_000L) { "Quota estimate is too large" }
        val input = estimate.toLong().coerceAtLeast(1)
        if (calculator.preset in setOf(AiQuotaCalculator.Preset.CHARACTERS, AiQuotaCalculator.Preset.INPUT_ONLY)) return input
        val output = calculator.outputReserve ?: (request as? JsonObject)?.let { body ->
            listOf("max_output_tokens", "max_completion_tokens", "max_tokens").firstNotNullOfOrNull { name ->
                body[name]?.let { requireNotNull((it as? JsonPrimitive)?.longOrNull) { "Output token limit is not an integer" } }
            }
        } ?: throw AiAdmissionException("Quota estimate requires an explicit output reserve or request output-token limit")
        require(output >= 0) { "Output reserve is negative" }
        return when (calculator.preset) {
            AiQuotaCalculator.Preset.INPUT_PLUS_OUTPUT -> Math.addExact(input, output)
            AiQuotaCalculator.Preset.OPENAI_MAXIMUM -> maxOf(input, output)
            else -> error("Unexpected quota calculator")
        }
    }

    private fun extract(source: AiQuotaValueSource, response: AiHttpQuotaFeedback): String? {
        val element = when (source) {
            is AiQuotaValueSource.Header -> {
                val values = response.headers.entries.filter { it.key.equals(source.name, ignoreCase = true) }.flatMap { it.value }
                if (values.isEmpty()) return null
                require(values.size == 1) { "Quota header has multiple values" }
                val pointer = source.jsonPointer ?: return values.single().trim()
                JsonPointer(pointer).resolve(Json.parseToJsonElement(values.single()))
            }
            is AiQuotaValueSource.Body -> {
                if (response.bodyUnavailable && response.status !in 200..299) return null
                require(!response.bodyUnavailable) { "Quota response body is not available as bounded JSON" }
                response.body?.let { JsonPointer(source.pointer).resolve(it) }
            }
        } ?: return null
        require(element is JsonPrimitive && element != JsonNull) { "Quota feedback must be a scalar" }
        return element.content
    }

    private fun parseDelay(value: String, format: AiTpmFeedbackPolicy.RetryFormat, response: AiHttpQuotaFeedback): Long {
        if (format == AiTpmFeedbackPolicy.RetryFormat.HTTP_DATE ||
            (format == AiTpmFeedbackPolicy.RetryFormat.RETRY_AFTER && value.toDoubleOrNull() == null)) {
            val target = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            val date = response.headers.entries.singleOrNull { it.key.equals("date", true) }?.value?.singleOrNull()
            val now = date?.let { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() } ?: java.time.Instant.now()
            return java.time.Duration.between(now, target).toMillis().coerceAtLeast(0)
        }
        val number = value.toDoubleOrNull() ?: throw AiAdmissionException("Quota retry delay is not numeric")
        require(number.isFinite() && number >= 0) { "Invalid quota retry delay" }
        val millis = ceil(number * if (format == AiTpmFeedbackPolicy.RetryFormat.MILLISECONDS) 1 else 1000)
        require(millis <= 86_400_000) { "Quota retry delay exceeds one day" }
        return millis.toLong()
    }

    internal suspend fun runCommand(command: AiQuotaCommand, input: JsonElement): String =
        withTimeoutOrNull(command.timeoutSeconds * 1000L) {
            commands.withPermit {
                coroutineScope {
                    val process = withContext(Dispatchers.IO) { ProcessBuilder(listOf(command.executable) + command.arguments).start() }
                    try {
                        val out = async(Dispatchers.IO) { readBounded(process.inputStream) }
                        val err = async(Dispatchers.IO) { readBounded(process.errorStream) }
                        val writer = async(Dispatchers.IO) { process.outputStream.use { it.write(input.toString().encodeToByteArray()); it.write(10) } }
                        val exit = runInterruptible(Dispatchers.IO) { process.waitFor() }
                        writer.await()
                        val result = out.await()
                        err.await()
                        require(exit == 0) { "Quota command exited with status $exit" }
                        result.decodeToString(throwOnInvalidSequence = true)
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                            process.destroyForcibly()
                            process.outputStream.close()
                            process.inputStream.close()
                            process.errorStream.close()
                        }
                    }
                }
            }
        } ?: throw AiAdmissionException("Quota command timed out")

    private fun readBounded(stream: InputStream): ByteArray {
        val bytes = stream.readNBytes(65_537)
        require(bytes.size <= 65_536) { "Quota command output exceeds 64 KiB" }
        return bytes
    }
}
