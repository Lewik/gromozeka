package com.gromozeka.bot.services

import com.gromozeka.bot.utils.sha256
import java.io.File

/**
 * Manages JAR files from resources - copies them to Gromozeka home directory
 * with smart hash-based synchronization.
 *
 * Responsibilities:
 * - Extract JAR files from application resources to filesystem
 * - Compare file hashes to avoid unnecessary overwrites
 * - Manage JAR lifecycle in Gromozeka home directory
 *
 * Architecture:
 * Build Process: mcp-proxy.jar → resources/mcp-jars/ → [This Manager] → gromozeka-home/
 */
object JarResourceManager {

    private const val MCP_PROXY_JAR_RESOURCE = "/mcp-jars/mcp-proxy.jar"
    private const val MCP_PROXY_JAR_FILENAME = "mcp-proxy.jar"

    /**
     * Ensures mcp-proxy.jar is available in Gromozeka home directory.
     *
     * Process:
     * 1. Load JAR from resources
     * 2. Check if target file exists and compare hashes
     * 3. Copy only if hashes differ or file doesn't exist
     *
     * @param settingsService Settings service to get Gromozeka home directory
     */
    fun ensureMcpProxyJar(settingsService: SettingsService) {
        val gromozekaHome = settingsService.gromozekaHome
        val targetFile = File(gromozekaHome, MCP_PROXY_JAR_FILENAME)

        println("[JAR Resource Manager] Ensuring mcp-proxy.jar in: ${targetFile.absolutePath}")

        // Load JAR from resources
        val resourceStream = JarResourceManager::class.java.getResourceAsStream(MCP_PROXY_JAR_RESOURCE)
            ?: throw IllegalStateException("MCP proxy JAR not found in resources: $MCP_PROXY_JAR_RESOURCE")

        val resourceBytes = resourceStream.use { it.readBytes() }
        val resourceHash = resourceBytes.sha256()

        println("[JAR Resource Manager] Resource JAR hash: $resourceHash")

        // Check if target file exists and compare hashes
        val shouldCopy = if (targetFile.exists()) {
            val targetHash = targetFile.sha256()
            println("[JAR Resource Manager] Target JAR hash: $targetHash")

            if (resourceHash == targetHash) {
                println("[JAR Resource Manager] Hashes match - no copy needed")
                false
            } else {
                println("[JAR Resource Manager] Hashes differ - will update JAR")
                true
            }
        } else {
            println("[JAR Resource Manager] Target JAR doesn't exist - will copy")
            true
        }

        if (shouldCopy) {
            // Ensure Gromozeka home directory exists
            gromozekaHome.mkdirs()

            // Copy JAR file
            targetFile.writeBytes(resourceBytes)
            println("[JAR Resource Manager] Copied mcp-proxy.jar to: ${targetFile.absolutePath}")

            // Verify copy
            val copiedHash = targetFile.sha256()
            if (copiedHash == resourceHash) {
                println("[JAR Resource Manager] Copy verification successful")
            } else {
                throw IllegalStateException("Copy verification failed - hashes don't match")
            }
        }
    }

    /**
     * Gets the absolute path to mcp-proxy.jar.
     *
     * Priority order:
     * 1. Gromozeka home directory (primary location)
     * 2. Build directory (fallback for development)
     *
     * @param settingsService Settings service to get Gromozeka home directory
     * @return Absolute path to mcp-proxy.jar
     * @throws IllegalStateException if JAR file doesn't exist at any location
     */
    fun getMcpProxyJarPath(settingsService: SettingsService): String {
        val jarFile = File(settingsService.gromozekaHome, MCP_PROXY_JAR_FILENAME)

        // First try: Gromozeka home directory (primary)
        if (jarFile.exists()) {
            return jarFile.absolutePath
        }

        // Fallback: Build directory (for development)
        println("[JAR Resource Manager] JAR not found in Gromozeka home, trying build directory...")
        val currentDir = File(System.getProperty("user.dir"))
        val buildJarPath = when (currentDir.name) {
            "bot" -> {
                File(currentDir.parentFile, "mcp-proxy/build/libs/mcp-proxy.jar")
            }

            "dev1", "dev", "release" -> {
                File(currentDir, "mcp-proxy/build/libs/mcp-proxy.jar")
            }

            else -> {
                File(currentDir, "mcp-proxy/build/libs/mcp-proxy.jar")
            }
        }

        if (buildJarPath.exists()) {
            println("[JAR Resource Manager] Using JAR from build directory: ${buildJarPath.absolutePath}")
            return buildJarPath.absolutePath
        }

        throw IllegalStateException(
            "MCP proxy JAR not found at any location. Tried:\n" +
                    "1. Gromozeka home: ${jarFile.absolutePath}\n" +
                    "2. Build directory: ${buildJarPath.absolutePath}\n" +
                    "Call ensureMcpProxyJar() first to initialize JAR files, or run './gradlew build'."
        )
    }
}