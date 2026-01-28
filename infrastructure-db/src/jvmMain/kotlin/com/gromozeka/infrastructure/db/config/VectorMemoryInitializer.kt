package com.gromozeka.infrastructure.db.config

import com.gromozeka.domain.repository.ConversationMessageSearchRepository
import klog.KLoggers
import kotlinx.coroutines.runBlocking
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["gromozeka.vector.enabled"], havingValue = "true", matchIfMissing = true)
class VectorMemoryInitializer(
    private val conversationMessageSearchRepository: ConversationMessageSearchRepository
) : ApplicationRunner {
    private val log = KLoggers.logger(this)

    override fun run(args: ApplicationArguments?) {
        log.info { "Initializing Neo4j conversation message indexes..." }

        runBlocking {
            try {
                conversationMessageSearchRepository.initializeIndexes()
                log.info { "Conversation message indexes initialized successfully (vector + fulltext)" }
            } catch (e: Exception) {
                log.warn(e) { "Failed to initialize conversation message indexes: ${e.message}" }
            }
        }
    }
}
