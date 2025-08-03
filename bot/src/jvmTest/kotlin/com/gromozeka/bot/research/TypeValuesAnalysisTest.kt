package com.gromozeka.bot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File

class TypeValuesAnalysisTest {

    @Test
    @Disabled("Research test - creates files. Run manually when needed.")
    fun analyzeTypeValuesInTodaySessions() {
        val analyzer = TypeValuesAnalyzer()
        val typeValues = analyzer.analyzeTodaySessionTypes()
        
        val output = buildString {
            appendLine("=== TYPE VALUES ANALYSIS (TODAY SESSIONS) ===")
            appendLine()
            
            typeValues.entries.sortedByDescending { it.value }.forEach { (typeValue, count) ->
                appendLine("'$typeValue': $count")
            }
            
            appendLine()
            appendLine("=== SUMMARY ===")
            appendLine("Total unique type values: ${typeValues.size}")
            appendLine("Total messages: ${typeValues.values.sum()}")
        }
        
        println(output)
        
        val outputFile = File("type-values-today.txt")
        outputFile.writeText(output)
        println("Results written to: ${outputFile.absolutePath}")
    }
    
    @Test
    @Disabled("Research test - creates files. Run manually when needed.")
    fun analyzeTypeValuesInAllSessions() {
        val analyzer = TypeValuesAnalyzer()
        val typeValues = analyzer.analyzeAllSessionTypes()
        
        val output = buildString {
            appendLine("=== TYPE VALUES ANALYSIS (ALL SESSIONS) ===")
            appendLine()
            
            typeValues.entries.sortedByDescending { it.value }.forEach { (typeValue, count) ->
                appendLine("'$typeValue': $count")
            }
            
            appendLine()
            appendLine("=== SUMMARY ===")
            appendLine("Total unique type values: ${typeValues.size}")
            appendLine("Total messages: ${typeValues.values.sum()}")
        }
        
        println(output)
        
        val outputFile = File("type-values-all.txt")
        outputFile.writeText(output)
        println("Results written to: ${outputFile.absolutePath}")
    }
}

class TypeValuesAnalyzer {
    private val objectMapper = ObjectMapper().registerKotlinModule()
    
    fun analyzeTodaySessionTypes(): Map<String, Int> {
        val typeValues = mutableMapOf<String, Int>()
        
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
                        val typeValue = json["type"] as? String ?: "null"
                        typeValues[typeValue] = typeValues.getOrDefault(typeValue, 0) + 1
                    }
                }
            }
        }
        
        return typeValues
    }
    
    fun analyzeAllSessionTypes(): Map<String, Int> {
        val typeValues = mutableMapOf<String, Int>()
        
        val gromozekaProjectDir = File(System.getProperty("user.home"), ".claude/projects/-Users-lewik-code-gromozeka")
        
        gromozekaProjectDir.walk()
            .filter { it.extension == "jsonl" }
            .forEach { file ->
                file.readLines().forEach { line ->
                    if (line.trim().isNotEmpty()) {
                        val json = objectMapper.readValue(line, Map::class.java) as Map<String, Any?>
                        val typeValue = json["type"] as? String ?: "null"
                        typeValues[typeValue] = typeValues.getOrDefault(typeValue, 0) + 1
                    }
                }
            }
        
        return typeValues
    }
}