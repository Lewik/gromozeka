package com.gromozeka.infrastructure.db.config

import com.gromozeka.infrastructure.db.memory.graph.ConversationMessageGraphService
import klog.KLoggers
import kotlinx.coroutines.runBlocking
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["gromozeka.vector.enabled"], havingValue = "true", matchIfMissing = true)
class VectorMemoryInitializer(
    private val conversationMessageGraphService: ConversationMessageGraphService
) : ApplicationRunner {
    private val log = KLoggers.logger(this)

    override fun run(args: ApplicationArguments?) {
        log.info { "Initializing Neo4j vector memory indexes..." }

        runBlocking {
            try {
                conversationMessageGraphService.initializeVectorIndex()
                log.info { "Vector memory indexes initialized successfully" }
            } catch (e: Exception) {
                log.warn(e) { "Failed to initialize vector memory indexes: ${e.message}" }
            }
        }
    }
}
