package com.gromozeka.bot.config

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.context.ApplicationListener
import org.springframework.core.env.Environment
import org.springframework.core.env.MapPropertySource

/**
 * Programmatically configures logging file path based on application mode and platform.
 * 
 * This listener intercepts ApplicationEnvironmentPreparedEvent to set logging.file.path
 * before the logging system initializes, ensuring single source of truth for log paths.
 * 
 * Timing: Executes after Environment is prepared but before ApplicationContext creation,
 * which is exactly when Spring Boot's logging system reads the logging properties.
 */
class LoggingPathListener : ApplicationListener<ApplicationEnvironmentPreparedEvent> {
    
    override fun onApplicationEvent(event: ApplicationEnvironmentPreparedEvent) {
        val environment = event.environment
        val logPath = determineLogPath(environment)
        
        // Add logging properties with highest priority using addFirst
        val loggingProps = mapOf(
            "logging.file.path" to logPath,
            "logging.pattern.rolling-file-name" to "$logPath/gromozeka-%d{yyyy-MM-dd}.%i.log"
        )
        
        environment.propertySources.addFirst(
            MapPropertySource("dynamicLogging", loggingProps)
        )
    }
    
    /**
     * Determines the appropriate log directory based on application mode and platform.
     * 
     * DEV mode: Uses local "logs" directory in project root
     * PROD mode: Uses platform-specific directories following OS conventions
     */
    private fun determineLogPath(environment: Environment): String {
        val mode = environment.getProperty("GROMOZEKA_MODE", "prod")
        val osName = System.getProperty("os.name").lowercase()
        val userHome = System.getProperty("user.home")
        
        return when (mode) {
            "dev" -> "logs"
            else -> when {
                osName.contains("mac") -> "$userHome/Library/Logs/Gromozeka"
                osName.contains("windows") -> "$userHome/AppData/Local/Gromozeka/logs"
                osName.contains("linux") -> "$userHome/.local/share/Gromozeka/logs"
                else -> "logs" // Fallback for unknown platforms
            }
        }
    }
}