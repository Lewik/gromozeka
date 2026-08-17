package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class MessageInstructionTextShortcutSettings(
    val separators: List<String> = listOf("/", "="),
) {
    init {
        require(separators.all { separator -> separator.isNotBlank() && separator.none(Char::isWhitespace) }) {
            "Message instruction text shortcut separators must not be blank or contain whitespace"
        }
        require(separators.distinct().size == separators.size) {
            "Message instruction text shortcut separators must be unique"
        }
    }
}

object MessageInstructionTextShortcut {
    private const val COMPLETION_SUFFIX = "  "

    data class Match(
        val remainingInput: String,
        val group: MessageInstructionGroup,
        val controlIndex: Int,
    )

    fun consume(
        input: String,
        settings: MessageInstructionTextShortcutSettings,
        groups: List<MessageInstructionGroup>,
    ): Match? {
        val candidates = buildList {
            groups.forEach { group ->
                group.controls.forEachIndexed { controlIndex, control ->
                    settings.separators.forEach { separator ->
                        control.textShortcutAliases.forEach { alias ->
                            val suffix = "$separator$alias$COMPLETION_SUFFIX"
                            if (input.endsWith(suffix, ignoreCase = true)) {
                                add(Candidate(group, controlIndex, suffix.length))
                            }
                        }
                    }
                }
            }
        }
        val longestSuffixLength = candidates.maxOfOrNull(Candidate::suffixLength) ?: return null
        val longestMatches = candidates
            .filter { candidate -> candidate.suffixLength == longestSuffixLength }
            .distinctBy { candidate -> candidate.group.id to candidate.controlIndex }
        val match = longestMatches.singleOrNull() ?: return null

        return Match(
            remainingInput = input.dropLast(match.suffixLength),
            group = match.group,
            controlIndex = match.controlIndex,
        )
    }

    private data class Candidate(
        val group: MessageInstructionGroup,
        val controlIndex: Int,
        val suffixLength: Int,
    )
}
