package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AgentDefinition
import klog.KLoggers
import kotlinx.serialization.json.Json
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component

@Component
class BuiltinAgentLoader {
    private val log = KLoggers.logger("BuiltinAgentLoader")
    private val resourceResolver = PathMatchingResourcePatternResolver()
    private val json = Json

    fun loadBuiltinAgents(): List<AgentDefinition> {
        val resources = resourceResolver.getResources("classpath*:/agents/*.json")
        check(resources.isNotEmpty()) {
            "No builtin agents found in classpath:/agents"
        }

        log.info { "Scanning for builtin agents: found ${resources.size} JSON files" }

        val agents = resources.map { resource ->
            val fileName = checkNotNull(resource.filename) {
                "Builtin agent resource has no filename: $resource"
            }
            val content = resource.inputStream.bufferedReader().use { it.readText() }

            log.debug { "Loading builtin agent from: $fileName" }

            val id = AgentDefinition.Id("builtin:$fileName")
            json.decodeFromString<AgentDefinition>(content)
                .validatedIdentity(id, AgentDefinition.Type.Builtin, fileName)
                .also { agent ->
                    log.info { "Loaded builtin agent: ${agent.name} (${agent.id.value})" }
                }
        }

        val duplicateIds = agents.groupBy { it.id }.filterValues { it.size > 1 }.keys
        check(duplicateIds.isEmpty()) {
            "Duplicate builtin agent ids: ${duplicateIds.joinToString { it.value }}"
        }

        log.info { "Loaded ${agents.size} builtin agents from /agents directory" }
        return agents
    }
}
