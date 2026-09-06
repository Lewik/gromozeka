package com.gromozeka.infrastructure.ai.claude

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeCodeResponseContractTest {
    private val contract = ClaudeCodeResponseContract(
        schema = Json.parseToJsonElement("""{
            "type":"object","additionalProperties":false,
            "properties":{
                "name":{"type":"string"},
                "count":{"type":"integer","minimum":1},
                "items":{"type":"array","minItems":1,"items":{"type":"string"}},
                "nullable":{"anyOf":[{"type":"null"},{"type":"string"}]}
            },
            "required":["name","count","items","nullable"]
        }"""),
        tools = emptyList(),
        externalActions = false,
    )
    private val valid = """{"name":"Привет","count":2,"items":["Да"],"nullable":null}"""

    @Test
    fun validatesNestedTypesAndRequiredFieldsWithoutCoercion() {
        assertTrue(contract.validationErrors(valid).isEmpty())
        for (invalid in listOf(
            valid.replace("\"count\":2", "\"count\":\"2\""),
            valid.replace("\"count\":2", "\"count\":0"),
            valid.replace("\"nullable\":null", "\"nullable\":false"),
            valid.replace("[\"Да\"]", "[]"),
            valid.replace("[\"Да\"]", "[1]"),
            valid.replace("\"name\":\"Привет\",", ""),
            valid.replace("\"name\":", "\"unexpected\":true,\"name\":"),
        )) assertFalse(contract.validationErrors(invalid).isEmpty(), invalid)
    }

    @Test
    fun rejectsSurroundingTextMultipleObjectsAndInvalidJson() {
        for (invalid in listOf("```json\n$valid\n```", "Here: $valid", "$valid extra", "$valid $valid", "[]", "null", "{name:2}", "{")) {
            assertFalse(contract.validationErrors(invalid).isEmpty(), invalid)
        }
        assertTrue(contract.validationErrors(" \n$valid\n ").isEmpty())
    }

    @Test
    fun rejectsInvalidJsonNumberSyntax() {
        for (number in listOf("02", "+2", "2.", "NaN", "Infinity")) {
            assertFalse(contract.validationErrors(valid.replace("\"count\":2", "\"count\":$number")).isEmpty(), number)
        }
    }

    @Test
    fun rejectsDuplicateKeysInsteadOfPickingLastValue() {
        assertFalse(contract.validationErrors(valid.replace("\"count\":2", "\"count\":1,\"count\":2")).isEmpty())
    }

    @Test
    fun resolvesLocalReferences() {
        val referenced = ClaudeCodeResponseContract(Json.parseToJsonElement("""{
            "type":"object",
            "${'$'}defs":{"value":{"type":"integer","minimum":3}},
            "properties":{"value":{"${'$'}ref":"#/${'$'}defs/value"}},
            "required":["value"]
        }"""), emptyList(), false)
        assertTrue(referenced.validationErrors("""{"value":3}""").isEmpty())
        assertFalse(referenced.validationErrors("""{"value":2}""").isEmpty())
    }

    @Test
    fun reminderReferencesSchemaWithoutCopyingIt() {
        val reminder = contract.reminder()
        assertTrue(reminder.contains("Do not add Markdown fences or text outside that JSON object."))
        assertFalse(reminder.contains("tool_calls"))
        assertFalse(reminder.contains(contract.schema.toString()))
        assertTrue(contract.instructions().contains(contract.schema.toString()))
    }
}
