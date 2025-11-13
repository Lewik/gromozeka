package com.gromozeka.bot.config

import com.gromozeka.bot.services.memory.graph.KnowledgeGraphService
import klog.KLoggers
import kotlinx.coroutines.runBlocking
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["knowledge-graph.enabled"], havingValue = "true", matchIfMissing = false)
class KnowledgeGraphInitializer(
    private val knowledgeGraphService: KnowledgeGraphService
) : ApplicationRunner {
    private val log = KLoggers.logger(this)

    override fun run(args: ApplicationArguments?) {
        log.info { "Initializing Neo4j knowledge graph..." }

        runBlocking {
            try {
                knowledgeGraphService.initializeIndexes()
                log.info { "Knowledge graph indexes initialized successfully" }
            } catch (e: Exception) {
                log.warn(e) { "Failed to initialize knowledge graph indexes: ${e.message}" }
            }
        }
    }
}
