package com.gromozeka.infrastructure.ai.openai.subscription

internal const val OPENAI_REASONING_ITEMS_METADATA_KEY = "openaiReasoningItems"
internal const val OPENAI_SUBSCRIPTION_ORIGINATOR = "codex_cli_rs"

internal fun openAiSubscriptionUserAgent(clientVersion: String): String =
    "$OPENAI_SUBSCRIPTION_ORIGINATOR/$clientVersion (Gromozeka; JVM)"
