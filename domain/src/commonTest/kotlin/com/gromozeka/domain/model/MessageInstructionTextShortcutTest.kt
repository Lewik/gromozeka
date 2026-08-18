package com.gromozeka.domain.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageInstructionTextShortcutTest {
    @Test
    fun consumesDefaultEnglishAndRussianAliases() {
        val groups = MessageInstructionGroup.defaults()
        val settings = MessageInstructionTextShortcutSettings()

        val readonly = MessageInstructionTextShortcut.consume("Inspect this/r  ", settings, groups)
        val writable = MessageInstructionTextShortcut.consume("Исправь это=Ц  ", settings, groups)

        assertEquals("Inspect this", readonly?.remainingInput)
        assertEquals("mode_readonly", readonly?.let { it.group.controls[it.controlIndex].data.id })
        assertEquals("Исправь это", writable?.remainingInput)
        assertEquals("mode_writable", writable?.let { it.group.controls[it.controlIndex].data.id })
    }

    @Test
    fun supportsMultiCharacterAliasesAndPrefersLongestSuffix() {
        val groups = listOf(
            instructionGroup("short", "r"),
            instructionGroup("long", "read"),
        )

        val match = MessageInstructionTextShortcut.consume(
            input = "Question/read  ",
            settings = MessageInstructionTextShortcutSettings(listOf("/")),
            groups = groups,
        )

        assertEquals("Question", match?.remainingInput)
        assertEquals("long", match?.group?.id)
    }

    @Test
    fun requiresTwoTrailingSpacesAtTheEnd() {
        val groups = MessageInstructionGroup.defaults()
        val settings = MessageInstructionTextShortcutSettings()

        assertNull(MessageInstructionTextShortcut.consume("Inspect/r ", settings, groups))
        assertNull(MessageInstructionTextShortcut.consume("Inspect/r  later", settings, groups))
    }

    @Test
    fun rejectsAmbiguousAliasesInsteadOfSelectingArbitrarily() {
        val groups = listOf(
            instructionGroup("first", "go"),
            instructionGroup("second", "go"),
        )

        val match = MessageInstructionTextShortcut.consume(
            input = "Task=go  ",
            settings = MessageInstructionTextShortcutSettings(listOf("=")),
            groups = groups,
        )

        assertNull(match)
    }

    @Test
    fun restoresDefaultAliasesWhenReadingAnOlderProfile() {
        val control = Json.decodeFromString<MessageInstructionGroup.Control>(
            """
            {
              "data": {
                "id": "mode_readonly",
                "title": "Readonly",
                "description": "Inspect only"
              },
              "shortLabel": "R"
            }
            """.trimIndent(),
        )

        assertEquals(listOf("r", "к"), control.textShortcutAliases)
    }

    @Test
    fun keepsInstructionHistoryWhenReadingAnOlderGroup() {
        val group = Json.decodeFromString<MessageInstructionGroup>(
            """
            {
              "id": "mode",
              "title": "Mode",
              "controls": [
                {
                  "data": {
                    "id": "mode_readonly",
                    "title": "Readonly",
                    "description": "Inspect only"
                  },
                  "shortLabel": "R"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(MessageInstructionGroup.RetentionMode.KEEP_HISTORY, group.retentionMode)
    }

    private fun instructionGroup(id: String, alias: String) = MessageInstructionGroup(
        id = id,
        title = id,
        controls = listOf(
            MessageInstructionGroup.Control(
                data = Conversation.Message.Instruction.UserInstruction(
                    id = "${id}_instruction",
                    title = id,
                    description = id,
                ),
                shortLabel = id.first().uppercase(),
                textShortcutAliases = listOf(alias),
            )
        ),
    )
}
