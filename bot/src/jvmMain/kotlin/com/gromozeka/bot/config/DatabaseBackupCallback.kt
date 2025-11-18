package com.gromozeka.bot.config

import com.gromozeka.bot.services.SettingsService
import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.name
import kotlin.streams.asSequence

@Component
class DatabaseBackupCallback(
    private val settingsService: SettingsService
) : Callback {
    
    private val logger = LoggerFactory.getLogger(javaClass)
    
    companion object {
        private const val MAX_BACKUPS = 5
        private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
    
    override fun supports(event: Event, context: Context): Boolean {
        return event == Event.BEFORE_EACH_MIGRATE
    }
    
    override fun canHandleInTransaction(event: Event, context: Context): Boolean {
        return true
    }
    
    override fun handle(event: Event, context: Context) {
        val migrationInfo = context.migrationInfo ?: return
        val version = migrationInfo.version?.toString() ?: "unknown"
        val description = migrationInfo.description
        
        val dbPath = settingsService.gromozekaHome.resolve("gromozeka.db").toPath()
        if (!Files.exists(dbPath)) {
            logger.warn("Database file not found: $dbPath")
            return
        }
        
        val backupsDir = settingsService.gromozekaHome.resolve("backups").toPath()
        Files.createDirectories(backupsDir)
        
        val timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER)
        val backupPath = backupsDir.resolve(
            "gromozeka.db.backup-v${version}-${timestamp}"
        )
        
        try {
            Files.copy(dbPath, backupPath)
            logger.info("Database backup created before migration V${version} (${description}): ${backupPath.name}")
            
            cleanupOldBackups()
        } catch (e: Exception) {
            logger.error("Failed to create database backup", e)
        }
    }
    
    private fun cleanupOldBackups() {
        try {
            val backupDir = settingsService.gromozekaHome.resolve("backups").toPath()
            if (!Files.exists(backupDir)) return
            
            val backups = Files.list(backupDir).asSequence()
                .filter { it.name.startsWith("gromozeka.db.backup-") }
                .sortedByDescending { Files.getLastModifiedTime(it) }
                .toList()
            
            if (backups.size > MAX_BACKUPS) {
                val toDelete = backups.drop(MAX_BACKUPS)
                toDelete.forEach { backup ->
                    Files.delete(backup)
                    logger.info("Deleted old backup: ${backup.name}")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to cleanup old backups", e)
        }
    }
    
    override fun getCallbackName() = "DatabaseBackupCallback"
}
