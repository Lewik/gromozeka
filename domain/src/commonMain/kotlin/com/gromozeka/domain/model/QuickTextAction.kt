package com.gromozeka.domain.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
data class QuickTextAction(
    val id: Id,
    val title: String,
    val description: String,
    val prompt: String,
    val agentId: AgentDefinition.Id? = null,
) {
    init {
        require(id.value.isNotBlank()) { "Quick text action id must not be blank" }
        require(title.isNotBlank()) { "Quick text action title must not be blank" }
        require(description.isNotBlank()) { "Quick text action description must not be blank" }
        require(prompt.isNotBlank()) { "Quick text action prompt must not be blank" }
    }

    @Serializable
    @JvmInline
    value class Id(val value: String)

    companion object {
        val FIX_TEXT_ID = Id("fix_text_preserve_language")
        val TRANSLATE_RU_EN_ID = Id("translate_ru_en")

        fun defaults(): List<QuickTextAction> = listOf(
            QuickTextAction(
                id = FIX_TEXT_ID,
                title = "Fix text",
                description = "Correct text while preserving its original language.",
                prompt = """
                    Correct spelling, grammar, punctuation, and awkward wording.
                    Preserve the original language, meaning, tone, paragraph structure, and line breaks.
                    Return only the corrected text.
                """.trimIndent(),
            ),
            QuickTextAction(
                id = TRANSLATE_RU_EN_ID,
                title = "Translate",
                description = "Translate Russian to English and non-Russian text to Russian.",
                prompt = """
                    If the input text is Russian, translate it to English.
                    If the input text is not Russian, translate it to Russian.
                    Preserve meaning, tone, paragraph structure, and line breaks.
                    Return only the translated text.
                """.trimIndent(),
            ),
        )
    }
}

@Serializable
data class QuickTextActionResult(
    val actionId: QuickTextAction.Id,
    val text: String,
) {
    init {
        require(text.isNotBlank()) { "Quick text action result must not be blank" }
    }
}
