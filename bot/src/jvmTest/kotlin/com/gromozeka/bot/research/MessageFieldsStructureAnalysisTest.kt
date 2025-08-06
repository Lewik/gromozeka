package com.gromozeka.bot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File

class MessageFieldsStructureAnalysisTest {

    @Test
    @Disabled("Research test - creates files. Run manually when needed.")
    fun analyzeMessageFieldStructure() {
        val analyzer = MessageFieldsStructureAnalyzer()
        val messageStructures = analyzer.analyzeMessageFieldByType()

        val output = buildString {
            appendLine("=== MESSAGE FIELD STRUCTURE ANALYSIS BY TYPE ===")
            appendLine()

            messageStructures.forEach { (type, structures) ->
                appendLine("# TYPE: '$type' (${structures.size} unique message structures)")
                appendLine()

                val paddedStructures = padStructureFieldsInGroup(structures.toList())
                val maxNumberWidth = structures.size.toString().length
                paddedStructures.forEachIndexed { i, structure ->
                    appendLine("${(i + 1).toString().padStart(maxNumberWidth)}. $structure")
                }
                appendLine()
                appendLine("=".repeat(60))
                appendLine()
            }
        }

        println(output)

        val outputFile = File("message-field-structures-analysis.txt")
        outputFile.writeText(output)
        println("Results written to: ${outputFile.absolutePath}")
    }

    @Test
    @Disabled("Research test - creates files. Run manually when needed.")
    fun analyzeToolUseResultFieldStructure() {
        val analyzer = MessageFieldsStructureAnalyzer()
        val toolUseResultStructures = analyzer.analyzeToolUseResultFieldByType()

        val output = buildString {
            appendLine("=== TOOLUSERESULT FIELD STRUCTURE ANALYSIS BY TYPE ===")
            appendLine()

            toolUseResultStructures.forEach { (type, structures) ->
                appendLine("# TYPE: '$type' (${structures.size} unique toolUseResult structures)")
                appendLine()

                val paddedStructures = padStructureFieldsInGroup(structures.toList())
                val maxNumberWidth = structures.size.toString().length
                paddedStructures.forEachIndexed { i, structure ->
                    appendLine("${(i + 1).toString().padStart(maxNumberWidth)}. $structure")
                }
                appendLine()
                appendLine("=".repeat(60))
                appendLine()
            }
        }

        println(output)

        val outputFile = File("tooluseresult-field-structures-analysis.txt")
        outputFile.writeText(output)
        println("Results written to: ${outputFile.absolutePath}")
    }

    @Test
    @Disabled("Research test - creates files. Run manually when needed.")
    fun analyzeBothMessageAndToolUseResultFields() {
        val analyzer = MessageFieldsStructureAnalyzer()
        val messageStructures = analyzer.analyzeMessageFieldByType()
        val toolUseResultStructures = analyzer.analyzeToolUseResultFieldByType()

        val output = buildString {
            appendLine("=== COMBINED MESSAGE AND TOOLUSERESULT FIELDS STRUCTURE ANALYSIS ===")
            appendLine()

            appendLine("## MESSAGE FIELD STRUCTURES")
            appendLine()
            messageStructures.forEach { (type, structures) ->
                appendLine("### TYPE: '$type' (${structures.size} unique message structures)")
                val paddedStructures = padStructureFieldsInGroup(structures.toList())
                val maxNumberWidth = structures.size.toString().length
                paddedStructures.forEachIndexed { i, structure ->
                    appendLine("${(i + 1).toString().padStart(maxNumberWidth)}. $structure")
                }
                appendLine()
            }

            appendLine("=".repeat(80))
            appendLine()

            appendLine("## TOOLUSERESULT FIELD STRUCTURES")
            appendLine()
            toolUseResultStructures.forEach { (type, structures) ->
                appendLine("### TYPE: '$type' (${structures.size} unique toolUseResult structures)")
                val paddedStructures = padStructureFieldsInGroup(structures.toList())
                val maxNumberWidth = structures.size.toString().length
                paddedStructures.forEachIndexed { i, structure ->
                    appendLine("${(i + 1).toString().padStart(maxNumberWidth)}. $structure")
                }
                appendLine()
            }
        }

        println(output)

        val outputFile = File("message-and-tooluseresult-fields-analysis.txt")
        outputFile.writeText(output)
        println("Results written to: ${outputFile.absolutePath}")
    }
}

class MessageFieldsStructureAnalyzer {
    private val objectMapper = ObjectMapper().registerKotlinModule()

    fun analyzeMessageFieldByType(): Map<String, Set<String>> {
        val resultsByType = mutableMapOf<String, MutableSet<String>>()

        // Analyze gromozeka sessions from today (2025-08-01)
        val todaySessionFiles = listOf(
            "8259ddd2-5761-41e6-ae94-2060663f3128.jsonl",
            "5eeceea8-df50-4031-813e-380cfe33be32.jsonl",
            "169a5857-47bd-4061-af0b-dd2e5dc2561e.jsonl",
            "4dbcf7e1-038a-4bb8-8042-4a9fdac1e9f3.jsonl",
            "bbd6b3ed-78ae-46e7-8061-b6db1a7dc084.jsonl",
            "b27fa4d0-2950-4051-bb6a-f67921015708.jsonl",
            "136e5bfa-d286-4426-99ca-7d633652f481.jsonl"
        )

        val gromozekaProjectDir = File(System.getProperty("user.home"), ".claude/projects/-Users-lewik-code-gromozeka")

        todaySessionFiles.forEach { filename ->
            val file = File(gromozekaProjectDir, filename)
            if (file.exists()) {
                file.readLines().forEach { line ->
                    if (line.trim().isNotEmpty()) {
                        val json = objectMapper.readValue(line, Map::class.java) as Map<String, Any?>
                        val typeValue = json["type"] as? String ?: "null"

                        // Extract message field if it exists
                        val messageField = json["message"]
                        if (messageField != null) {
                            val messageStructure = extractStructure(messageField)
                            resultsByType
                                .computeIfAbsent(typeValue) { mutableSetOf() }
                                .add(messageStructure)
                        }
                    }
                }
            }
        }

        return resultsByType
    }

    fun analyzeToolUseResultFieldByType(): Map<String, Set<String>> {
        val resultsByType = mutableMapOf<String, MutableSet<String>>()

        // Analyze gromozeka sessions from today (2025-08-01)
        val todaySessionFiles = listOf(
            "8259ddd2-5761-41e6-ae94-2060663f3128.jsonl",
            "5eeceea8-df50-4031-813e-380cfe33be32.jsonl",
            "169a5857-47bd-4061-af0b-dd2e5dc2561e.jsonl",
            "4dbcf7e1-038a-4bb8-8042-4a9fdac1e9f3.jsonl",
            "bbd6b3ed-78ae-46e7-8061-b6db1a7dc084.jsonl",
            "b27fa4d0-2950-4051-bb6a-f67921015708.jsonl",
            "136e5bfa-d286-4426-99ca-7d633652f481.jsonl"
        )

        val gromozekaProjectDir = File(System.getProperty("user.home"), ".claude/projects/-Users-lewik-code-gromozeka")

        todaySessionFiles.forEach { filename ->
            val file = File(gromozekaProjectDir, filename)
            if (file.exists()) {
                file.readLines().forEach { line ->
                    if (line.trim().isNotEmpty()) {
                        val json = objectMapper.readValue(line, Map::class.java) as Map<String, Any?>
                        val typeValue = json["type"] as? String ?: "null"

                        // Extract toolUseResult field if it exists
                        val toolUseResultField = json["toolUseResult"]
                        if (toolUseResultField != null) {
                            val toolUseResultStructure = extractStructure(toolUseResultField)
                            resultsByType
                                .computeIfAbsent(typeValue) { mutableSetOf() }
                                .add(toolUseResultStructure)
                        }
                    }
                }
            }
        }

        return resultsByType
    }

    private fun extractStructure(json: Any?): String {
        return when (json) {
            null -> "null"
            is String -> "String"
            is Number -> "Number"
            is Boolean -> "Boolean"
            is Map<*, *> -> {
                val sortedFields = json.keys
                    .filterIsInstance<String>()
                    .sorted()
                    .map { key -> "$key: ${extractStructure(json[key])}" }
                    .joinToString(", ")
                "Object{$sortedFields}"
            }

            is List<*> -> {
                if (json.isEmpty()) {
                    "Array[]"
                } else {
                    val elementTypes = json.map { extractStructure(it) }.toSet()
                    val arrayType = if (elementTypes.size == 1) {
                        elementTypes.first()
                    } else {
                        elementTypes.joinToString(" | ")
                    }
                    "Array[$arrayType]"
                }
            }

            else -> json::class.simpleName ?: "Unknown"
        }
    }
}