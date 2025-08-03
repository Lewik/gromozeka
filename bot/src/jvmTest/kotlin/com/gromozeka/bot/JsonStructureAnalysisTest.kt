package com.gromozeka.bot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Test
import java.io.File

class JsonStructureAnalysisTest {

    @Test
    fun discoverAllClaudeJsonStructures() {
        val analyzer = JsonStructureAnalyzer()
        val analysisResults = analyzer.analyzeByTypeAndGroup()
        
        val output = buildString {
            appendLine("=== ANALYSIS BY TYPE AND GROUP ===")
            appendLine()
            
            analysisResults.forEach { (type, groupsData) ->
                appendLine("# TYPE: '$type' (${groupsData.values.sumOf { it.size }} messages)")
                appendLine()
                
                groupsData.forEach { (group, structures) ->
                    appendLine("## $group (${structures.size} structures)")
                    val paddedStructures = padStructureFieldsInGroup(structures.toList())
                    val maxNumberWidth = structures.size.toString().length
                    paddedStructures.forEachIndexed { i, structure ->
                        appendLine("${(i+1).toString().padStart(maxNumberWidth)}. $structure")
                    }
                    appendLine()
                }
                appendLine("=".repeat(60))
                appendLine()
            }
        }
        
        println(output)
        
        val outputFile = File("json-structures-analysis.txt")
        outputFile.writeText(output)
        println("Results written to: ${outputFile.absolutePath}")
    }
    
    @Test
    fun discoverJsonStructuresWithoutMessage() {
        val analyzer = JsonStructureAnalyzer()
        val analysisResults = analyzer.analyzeByTypeAndGroupWithoutMessage()
        
        val output = buildString {
            appendLine("=== ANALYSIS BY TYPE AND GROUP (WITHOUT MESSAGE AND TOOLUSERESULT FIELDS) ===")
            appendLine()
            
            analysisResults.forEach { (type, groupsData) ->
                appendLine("# TYPE: '$type' (${groupsData.values.sumOf { it.size }} messages)")
                appendLine()
                
                groupsData.forEach { (group, structures) ->
                    appendLine("## $group (${structures.size} structures)")
                    val paddedStructures = padStructureFieldsInGroup(structures.toList())
                    val maxNumberWidth = structures.size.toString().length
                    paddedStructures.forEachIndexed { i, structure ->
                        appendLine("${(i+1).toString().padStart(maxNumberWidth)}. $structure")
                    }
                    appendLine()
                }
                appendLine("=".repeat(60))
                appendLine()
            }
        }
        
        println(output)
        
        val outputFile = File("json-structures-analysis-without-message.txt")
        outputFile.writeText(output)
        println("Results written to: ${outputFile.absolutePath}")
    }
}

class JsonStructureAnalyzer {
    private val objectMapper = ObjectMapper().registerKotlinModule()
    
    fun analyzeByTypeAndGroup(): Map<String, Map<String, Set<String>>> {
        val resultsByType = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()
        
        // Only analyze gromozeka sessions from today (2025-08-01)
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
                        val structure = extractStructure(json)
                        val group = categorizeStructure(structure)
                        val typeValue = json["type"] as? String ?: "null"
                        
                        resultsByType
                            .computeIfAbsent(typeValue) { mutableMapOf() }
                            .computeIfAbsent(group) { mutableSetOf() }
                            .add(structure)
                    }
                }
            }
        }
        
        return resultsByType
    }
    
    fun analyzeByTypeAndGroupWithoutMessage(): Map<String, Map<String, Set<String>>> {
        val resultsByType = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()
        
        // Only analyze gromozeka sessions from today (2025-08-01)
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
                        val jsonWithoutFields = json.filterKeys { it != "message" && it != "toolUseResult" }
                        val structure = extractStructure(jsonWithoutFields)
                        val group = categorizeStructureWithoutMessage(structure)
                        val typeValue = json["type"] as? String ?: "null"
                        
                        resultsByType
                            .computeIfAbsent(typeValue) { mutableMapOf() }
                            .computeIfAbsent(group) { mutableSetOf() }
                            .add(structure)
                    }
                }
            }
        }
        
        return resultsByType
    }
    
    private fun extractTopLevelFields(json: Map<String, Any?>): Set<String> {
        return json.keys.toSet()
    }
    
    private fun groupStructures(structures: Set<String>): Map<String, Set<String>> {
        val groups = mutableMapOf<String, MutableSet<String>>()
        
        structures.forEach { structure ->
            val group = categorizeStructure(structure)
            groups.computeIfAbsent(group) { mutableSetOf() }.add(structure)
        }
        
        return groups
    }
    
    private fun categorizeStructure(structure: String): String {
        return when {
            // User messages
            structure.contains("message: Object{content: String, role: String}") ||
            structure.contains("message: Object{content: Array[Object{text: String, type: String}], role: String}") -> "USER_MESSAGES"
            
            // Assistant messages (have model, usage, but no tool calls)
            structure.contains("message: Object{content: Array[Object{text: String, type: String}]") && 
            structure.contains("model: String") && 
            structure.contains("usage: Object") &&
            !structure.contains("input: Object") -> "ASSISTANT_MESSAGES"
            
            // Thinking messages
            structure.contains("thinking: String") && structure.contains("signature: String") -> "ASSISTANT_THINKING_MESSAGES"
            
            // Tool use messages (assistant calling tools)
            structure.contains("input: Object") && structure.contains("name: String") -> "TOOL_USE_MESSAGES"
            
            // Tool result messages (user providing tool results)
            structure.contains("toolUseResult:") -> "TOOL_RESULT_MESSAGES"
            
            // Summary messages
            structure.contains("leafUuid: String") && structure.contains("summary: String") -> "SUMMARY_MESSAGES"
            
            // System/Meta messages
            structure.contains("isMeta: Boolean") || structure.contains("level: String") -> "SYSTEM_META_MESSAGES"
            
            // Compact summary messages
            structure.contains("isCompactSummary: Boolean") -> "COMPACT_SUMMARY_MESSAGES"
            
            else -> "UNCATEGORIZED"
        }
    }
    
    private fun categorizeStructureWithoutMessage(structure: String): String {
        return when {
            // Assistant messages (have model, usage, requestId)
            structure.contains("model: String") && structure.contains("usage: Object") && structure.contains("requestId: String") -> "ASSISTANT_MESSAGES"
            
            // System/Meta messages
            structure.contains("isMeta: Boolean") || structure.contains("level: String") -> "SYSTEM_META_MESSAGES"
            
            // Summary messages  
            structure.contains("leafUuid: String") && structure.contains("summary: String") -> "SUMMARY_MESSAGES"
            
            // Regular user messages (all user type messages without message and toolUseResult)
            structure.contains("userType: String") -> "USER_MESSAGES"
            
            else -> "UNCATEGORIZED"
        }
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
    
    private fun padStructureFields(structure: String): String {
        if (!structure.startsWith("Object{") || !structure.endsWith("}")) {
            return structure
        }
        
        val content = structure.substring(7, structure.length - 1)
        val fields = content.split(", ")
        
        if (fields.size <= 1) return structure
        
        val maxKeyLength = fields.maxOfOrNull { field ->
            field.substringBefore(":").length
        } ?: 0
        
        val paddedFields = fields.map { field ->
            val parts = field.split(": ", limit = 2)
            if (parts.size == 2) {
                "${parts[0].padEnd(maxKeyLength)}: ${parts[1]}"
            } else {
                field
            }
        }
        
        return "Object{${paddedFields.joinToString(", ")}}"
    }
}

fun padStructureFields(structure: String): String {
    if (!structure.startsWith("Object{") || !structure.endsWith("}")) {
        return structure
    }
    
    val content = structure.substring(7, structure.length - 1)
    val fields = content.split(", ")
    
    if (fields.size <= 1) return structure
    
    val maxKeyLength = fields.maxOfOrNull { field ->
        field.substringBefore(":").length
    } ?: 0
    
    val paddedFields = fields.map { field ->
        val parts = field.split(": ", limit = 2)
        if (parts.size == 2) {
            "${parts[0].padEnd(maxKeyLength)}: ${parts[1]}"
        } else {
            field
        }
    }
    
    return "Object{${paddedFields.joinToString(", ")}}"
}

fun padStructureFieldsInGroup(structures: List<String>): List<String> {
    if (structures.isEmpty()) return structures
    
    // Собираем все поля из всех структур в группе
    val allFields = mutableSetOf<String>()
    
    structures.forEach { structure ->
        if (structure.startsWith("Object{") && structure.endsWith("}")) {
            val content = structure.substring(7, structure.length - 1)
            val fields = content.split(", ")
            fields.forEach { field ->
                val key = field.substringBefore(":")
                allFields.add(key)
            }
        }
    }
    
    // Находим максимальную длину поля среди всех структур группы
    val maxKeyLength = allFields.maxOfOrNull { it.length } ?: 0
    
    // Применяем единый паддинг ко всем структурам
    return structures.map { structure ->
        if (!structure.startsWith("Object{") || !structure.endsWith("}")) {
            structure
        } else {
            val content = structure.substring(7, structure.length - 1)
            val fields = content.split(", ")
            
            if (fields.size <= 1) {
                structure
            } else {
                val paddedFields = fields.map { field ->
                    val parts = field.split(": ", limit = 2)
                    if (parts.size == 2) {
                        "${parts[0].padEnd(maxKeyLength)}: ${parts[1]}"
                    } else {
                        field
                    }
                }
                "Object{${paddedFields.joinToString(", ")}}"
            }
        }
    }
}