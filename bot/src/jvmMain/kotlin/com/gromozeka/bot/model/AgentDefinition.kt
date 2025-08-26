package com.gromozeka.bot.model

/**
 * Defines an AI agent with name and system prompt
 */
data class AgentDefinition(
    val name: String,    // Display name for UI and coordination
    val prompt: String,   // System prompt content defining behavior
) {
    companion object {

        /**
         * Create agent definition from resource file
         */
        fun fromFile(name: String, filename: String): AgentDefinition {
            val prompt = loadResourceFile("/prompts/$filename")
                ?: error("Failed to load agent prompt from $filename")
            return AgentDefinition(name, prompt)
        }

        /**
         * Create agent definition with inline prompt
         */
        fun fromInline(name: String, prompt: String) = AgentDefinition(name, prompt)

        /**
         * Default Gromozeka agent
         */
        val DEFAULT by lazy {
            fromFile("Gromozeka", "default-agent.md")
        }

        private fun loadResourceFile(path: String): String? = AgentDefinition::class.java
            .getResourceAsStream(path)
            ?.bufferedReader()?.use { it.readText() }

    }
}