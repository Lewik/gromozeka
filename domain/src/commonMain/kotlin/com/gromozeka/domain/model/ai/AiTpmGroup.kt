package com.gromozeka.domain.model.ai

import com.gromozeka.shared.json.JsonPointer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement
import kotlin.jvm.JvmInline
import kotlin.time.Instant

@Serializable
data class AiTpmGroup(
    val id: Id,
    val key: String,
    val displayName: String,
    val tokenLimit: Long,
    val renewalPeriodSeconds: Int = 60,
    val allowParallelRequests: Boolean = false,
    val quotaUsageFraction: Double = 1.0,
    val missingRetryDelaySeconds: Int = 10,
) {
    init {
        requireAiQuotaKey(key)
        require(displayName.isNotBlank() && displayName.length <= 255) { "Invalid TPM group display name" }
        require(tokenLimit in 1..1_000_000_000_000L) { "TPM token limit must be between 1 and 1000000000000" }
        require(renewalPeriodSeconds in 1..86400) { "Invalid TPM renewal period" }
        require(quotaUsageFraction.isFinite() && quotaUsageFraction > 0 && quotaUsageFraction <= 1) { "TPM quota fraction must be greater than zero and at most one" }
        require(tokenLimit * quotaUsageFraction >= 1) { "Usable TPM capacity must be at least one unit" }
        require(missingRetryDelaySeconds in 1..3600) { "Invalid TPM retry delay" }
    }

    @Serializable @JvmInline value class Id(val value: String) {
        init { require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "Invalid TPM group id" } }
    }
}

fun requireAiQuotaKey(key: String) {
    require(key.isNotBlank() && key.length <= 512 && key == key.trim() && key.none { it.code < 32 || it.code == 127 }) {
        "Quota key must contain 1 to 512 characters without outer whitespace or control characters"
    }
}

/** Runs only on Server. Request/response JSON is stdin, never interpolated into the command. */
@Serializable
data class AiQuotaCommand(val executable: String, val arguments: List<String> = emptyList(), val timeoutSeconds: Int = 10) {
    init {
        require(executable.isNotBlank() && executable.length <= 4096 && '\u0000' !in executable) { "Invalid quota command executable" }
        require(arguments.size <= 64 && arguments.all { it.length <= 4096 && '\u0000' !in it }) { "Invalid quota command arguments" }
        require(timeoutSeconds in 1..120) { "Quota command timeout must be between 1 and 120 seconds" }
    }
}

@Serializable
sealed interface AiTpmGroupSelector {
    @Serializable @SerialName("fixed") data class Fixed(val groupId: AiTpmGroup.Id) : AiTpmGroupSelector
    /** A string value from the exact HTTP request; for example /model or /reasoning/effort. */
    @Serializable @SerialName("request_value") data class RequestValue(val pointer: String) : AiTpmGroupSelector {
        init { JsonPointer(pointer) }
    }
    /** stdout is a single plain-text group key. */
    @Serializable @SerialName("command") data class Command(val command: AiQuotaCommand) : AiTpmGroupSelector
}

@Serializable
sealed interface AiQuotaCalculator {
    @Serializable @SerialName("builtin") data class BuiltIn(
        val preset: Preset = Preset.CHARACTERS,
        val charactersPerUnit: Double = 4.0,
        val multiplier: Double = 1.0,
        val fixedOverhead: Long = 0,
        val outputReserve: Long? = null,
    ) : AiQuotaCalculator {
        init {
            require(charactersPerUnit.isFinite() && charactersPerUnit > 0) { "Characters per quota unit must be positive" }
            require(multiplier.isFinite() && multiplier > 0) { "Quota multiplier must be positive" }
            require(fixedOverhead in 0..1_000_000_000_000L) { "Invalid quota overhead" }
            require(outputReserve == null || outputReserve in 0..1_000_000_000_000L) { "Invalid output reserve" }
        }
    }
    /** stdout is a JSON integer of estimated quota units. */
    @Serializable @SerialName("command") data class Command(val command: AiQuotaCommand) : AiQuotaCalculator

    @Serializable enum class Preset {
        CHARACTERS, INPUT_ONLY, INPUT_PLUS_OUTPUT, OPENAI_MAXIMUM;
        val sourceUrl: String get() = when (this) {
            CHARACTERS -> "https://help.openai.com/en/articles/4936856-understanding-and-counting-tokens"
            INPUT_ONLY -> "https://platform.claude.com/docs/en/api/rate-limits"
            INPUT_PLUS_OUTPUT -> "https://www.truefoundry.com/docs/ai-gateway/ratelimiting-v2"
            OPENAI_MAXIMUM -> "https://developers.openai.com/api/docs/guides/rate-limits"
        }
        val formula: String get() = when (this) {
            CHARACTERS -> "ceil(JSON code points / charactersPerUnit × multiplier) + fixedOverhead"
            INPUT_ONLY -> "ceil(text input code points / charactersPerUnit × multiplier) + fixedOverhead; no assumed cache discount"
            INPUT_PLUS_OUTPUT -> "estimated input + outputReserve (or the request output-token limit); local reservation, not measured usage"
            OPENAI_MAXIMUM -> "max(estimated input, outputReserve or request output-token limit); documented rate estimate, not billing"
        }
    }
}

@Serializable
sealed interface AiQuotaValueSource {
    @Serializable @SerialName("header") data class Header(val name: String, val jsonPointer: String? = null) : AiQuotaValueSource {
        init {
            require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "Invalid quota header name" }
            jsonPointer?.let(::JsonPointer)
        }
    }
    @Serializable @SerialName("body") data class Body(val pointer: String) : AiQuotaValueSource {
        init { JsonPointer(pointer) }
    }
}

@Serializable
sealed interface AiTpmFeedbackPolicy {
    @Serializable @SerialName("fields") data class Fields(
        val remaining: AiQuotaValueSource,
        val retryDelay: AiQuotaValueSource? = null,
        val retryFormat: RetryFormat = RetryFormat.SECONDS,
    ) : AiTpmFeedbackPolicy
    /** stdin: {status, headers, body}; stdout: {remaining: number?, retryAfterMillis: integer?}. */
    @Serializable @SerialName("command") data class Command(val command: AiQuotaCommand) : AiTpmFeedbackPolicy
    @Serializable enum class RetryFormat { SECONDS, MILLISECONDS, HTTP_DATE, RETRY_AFTER }
}

@Serializable
data class AiTpmPolicy(
    val group: AiTpmGroupSelector,
    val calculator: AiQuotaCalculator,
    val feedback: AiTpmFeedbackPolicy,
    /** Explicit fallback only for group selection, never for calculation or response failures. */
    val fallbackGroupId: AiTpmGroup.Id? = null,
)

@Serializable
data class AiHttpQuotaFeedback(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: JsonElement? = null,
    val bodyUnavailable: Boolean = false,
) { init { require(status in 100..599) } }

@Serializable
data class AiTpmReservation(
    val groupId: AiTpmGroup.Id,
    val estimatedUnits: Long,
    val feedback: AiTpmFeedbackPolicy,
    val cumulativeBefore: Long = 0,
    val outstandingBefore: Long = 0,
) {
    init { require(estimatedUnits in 1..1_000_000_000_000L && cumulativeBefore >= 0 && outstandingBefore >= 0) }
}

/** Durable balances. Recovery deliberately does not invent refill for time spent offline. */
@Serializable
data class AiTpmState(
    val groupId: AiTpmGroup.Id,
    val localRemaining: Double,
    val observedRemaining: Double,
    val cumulativeAdmitted: Long,
    val cooldownUntil: Instant? = null,
) {
    init { require(localRemaining.isFinite() && observedRemaining.isFinite() && cumulativeAdmitted >= 0) }
}

@Serializable
data class AiTpmFeedback(val remaining: Double? = null, val retryAfterMillis: Long? = null) {
    init {
        require(remaining == null || (remaining.isFinite() && kotlin.math.abs(remaining) <= 1e15)) { "Invalid TPM remaining quota" }
        require(retryAfterMillis == null || retryAfterMillis in 0..86_400_000L) { "Invalid TPM retry delay" }
    }
}

@Serializable
data class AiAdmissionCompletion(val lease: AiAdmissionLease, val outcome: AiAdmissionOutcome, val feedback: AiHttpQuotaFeedback? = null)

@Serializable
data class AiAdmissionCompletionResult(val feedbackError: String? = null)
