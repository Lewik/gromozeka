package com.gromozeka.bot.repository.exposed

import com.gromozeka.bot.repository.exposed.tables.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun main() {
    val dbPath = Paths.get("build/temp-schema.db")
    Files.deleteIfExists(dbPath)

    val database = Database.connect(
        url = "jdbc:sqlite:$dbPath",
        driver = "org.sqlite.JDBC"
    )

    val allTables = arrayOf(
        Projects,
        Conversations,
        Threads,
        Messages,
        ThreadMessages,
        ToolExecutions,
        Contexts,
        Agents
    )

    val statements = transaction(database) {
        MigrationUtils.statementsRequiredForDatabaseMigration(
            tables = allTables,
            withLogs = true
        )
    }

    if (statements.isEmpty()) {
        println("No schema changes detected.")
        Files.deleteIfExists(dbPath)
        return
    }

    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
    val version = generateNextVersion()
    val fileName = "V${version}__Schema_update_$timestamp.sql"
    val migrationPath = Paths.get("bot/src/jvmMain/resources/db/migration/$fileName")

    val content = buildString {
        appendLine("-- Generated migration")
        appendLine("-- Timestamp: ${LocalDateTime.now()}")
        appendLine()
        statements.forEach { statement ->
            appendLine(statement.trim())
            appendLine()
        }
    }

    Files.write(
        migrationPath,
        content.toByteArray(),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING
    )

    println("Migration created: $fileName")
    println("Statements:")
    statements.forEach { println("  ${it.trim()}") }

    Files.deleteIfExists(dbPath)
}

private fun generateNextVersion(): String {
    val migrationDir = Paths.get("bot/src/jvmMain/resources/db/migration")

    if (!Files.exists(migrationDir)) {
        return "2"
    }

    val existingVersions = Files.list(migrationDir).use { stream ->
        stream
            .filter { it.fileName.toString().startsWith("V") }
            .map { file ->
                val name = file.fileName.toString()
                val versionStr = name.substringAfter("V").substringBefore("__")
                versionStr.toIntOrNull() ?: 0
            }
            .toList()
    }

    val maxVersion = existingVersions.maxOrNull() ?: 1
    return (maxVersion + 1).toString()
}
