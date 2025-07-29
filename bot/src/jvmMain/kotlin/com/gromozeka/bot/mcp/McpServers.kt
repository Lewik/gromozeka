package com.gromozeka.bot.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Paths

private val mcpJson = Json { ignoreUnknownKeys = true }


@Serializable
data class McpServers(
    val mcpServers: Map<String, Config>,
) {

    @Serializable
    data class Config(
        val command: String,
        val args: List<String>,
        val env: Map<String, String>? = null,
    )

    companion object {
        private const val USER_CONFIG_FILENAME = ".chat.mcp.json"
        
        fun load(): McpServers {
            val userConfig = loadFromUserHome()
            if (userConfig != null) {
                return userConfig
            }
            
            return loadFromResource()
        }
        
        private fun loadFromUserHome(): McpServers? {
            val userHome = System.getProperty("user.home")
            val configFile = Paths.get(userHome, USER_CONFIG_FILENAME).toFile()
            
            if (!configFile.exists() || !configFile.isFile) {
                return null
            }
            
            return try {
                val jsonConfig = configFile.readText()
                loadMCPConfig(jsonConfig)
            } catch (e: Exception) {
                println("Ошибка при чтении пользовательской конфигурации MCP: ${e.message}")
                null
            }
        }
        
        private fun loadFromResource(): McpServers {
            val resourceStream = McpServers::class.java.classLoader.getResourceAsStream("mcp.json")
                ?: throw IllegalStateException("Не удалось загрузить конфигурацию MCP из ресурса")

            val jsonConfig = resourceStream.bufferedReader().use { it.readText() }
            return loadMCPConfig(jsonConfig)
        }
    }
}


private fun loadMCPConfig(jsonConfig: String): McpServers = mcpJson.decodeFromString(jsonConfig)


fun McpServers.getServer(name: String): McpServers.Config? {
    return mcpServers[name]
}
