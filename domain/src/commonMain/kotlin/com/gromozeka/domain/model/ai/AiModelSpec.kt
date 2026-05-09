package com.gromozeka.domain.model.ai

data class AiModelSpec(
    val id: String,
    val provider: Provider,
    val contextWindowTokens: Int,
    val autoCompaction: AutoCompaction? = null,
) {
    init {
        require(id.isNotBlank()) { "AI model id must not be blank" }
        require(contextWindowTokens > 0) { "AI model context window must be positive" }
        val threshold = autoCompaction?.thresholdTokens(contextWindowTokens)
        require(threshold == null || threshold in 1..contextWindowTokens) {
            "AI model auto compaction threshold must be inside context window"
        }
    }

    val autoCompactionThresholdTokens: Int?
        get() = autoCompaction?.thresholdTokens(contextWindowTokens)

    enum class Provider {
        ANTHROPIC,
        CLAUDE_CODE,
        OPEN_AI,
        OPEN_AI_SUBSCRIPTION,
        GEMINI,
        OLLAMA,
    }

    sealed interface AutoCompaction {
        fun thresholdTokens(contextWindowTokens: Int): Int

        data class Percent(val value: Int) : AutoCompaction {
            init {
                require(value in 1..100) { "AI model auto compaction percent must be in 1..100" }
            }

            override fun thresholdTokens(contextWindowTokens: Int): Int =
                (contextWindowTokens.toLong() * value / 100).toInt()
        }

        data class Absolute(val tokens: Int) : AutoCompaction {
            init {
                require(tokens > 0) { "AI model auto compaction threshold must be positive" }
            }

            override fun thresholdTokens(contextWindowTokens: Int): Int = tokens
        }
    }
}

object AiModelSpecs {
    private val percent80 = AiModelSpec.AutoCompaction.Percent(80)

    val byProviderAndId: Map<Pair<AiModelSpec.Provider, String>, AiModelSpec> = listOf(
        AiModelSpec("claude-opus-4-6", AiModelSpec.Provider.ANTHROPIC, 1_000_000, percent80),
        AiModelSpec("claude-sonnet-4-6", AiModelSpec.Provider.ANTHROPIC, 1_000_000, percent80),
        AiModelSpec("claude-sonnet-4-5-20250929", AiModelSpec.Provider.ANTHROPIC, 200_000, percent80),
        AiModelSpec("claude-haiku-4-5-20251001", AiModelSpec.Provider.ANTHROPIC, 200_000, percent80),
        AiModelSpec("claude-3-5-sonnet-20241022", AiModelSpec.Provider.ANTHROPIC, 200_000, percent80),
        AiModelSpec("claude-3-5-haiku-20241022", AiModelSpec.Provider.ANTHROPIC, 200_000, percent80),
        AiModelSpec("claude-3-opus-20240229", AiModelSpec.Provider.ANTHROPIC, 200_000, percent80),
        AiModelSpec("claude-sonnet-4-5", AiModelSpec.Provider.CLAUDE_CODE, 200_000, percent80),
        AiModelSpec("sonnet", AiModelSpec.Provider.CLAUDE_CODE, 1_000_000, percent80),
        AiModelSpec("opus", AiModelSpec.Provider.CLAUDE_CODE, 1_000_000, percent80),
        AiModelSpec("haiku", AiModelSpec.Provider.CLAUDE_CODE, 200_000, percent80),
        AiModelSpec("gemini-2.0-flash-exp", AiModelSpec.Provider.GEMINI, 1_000_000, percent80),
        AiModelSpec("gemini-1.5-pro", AiModelSpec.Provider.GEMINI, 2_000_000, percent80),
        AiModelSpec("gemini-1.5-flash", AiModelSpec.Provider.GEMINI, 1_000_000, percent80),
        AiModelSpec("gpt-4-turbo", AiModelSpec.Provider.OPEN_AI, 128_000, percent80),
        AiModelSpec("gpt-4o", AiModelSpec.Provider.OPEN_AI, 128_000, percent80),
        AiModelSpec("gpt-5.3-codex", AiModelSpec.Provider.OPEN_AI_SUBSCRIPTION, 400_000, percent80),
        AiModelSpec("gpt-5.4", AiModelSpec.Provider.OPEN_AI_SUBSCRIPTION, 1_050_000, percent80),
    ).associateBy { it.provider to it.id }
}
