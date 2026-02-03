package com.gromozeka.infrastructure.db.config

import com.gromozeka.infrastructure.db.graph.Neo4jPlanRepository
import klog.KLoggers
import kotlinx.coroutines.runBlocking
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Initializes Neo4j indexes for Plan Management System on application startup.
 *
 * Creates:
 * - plan_vector: HNSW vector index for semantic search
 * - plan_fulltext: Fulltext index for keyword search
 * - step_vector: HNSW vector index for step search
 */
@Component
class PlanIndexInitializer(
    private val planRepository: Neo4jPlanRepository
) : ApplicationRunner {
    private val log = KLoggers.logger(this)

    override fun run(args: ApplicationArguments?) {
        log.info { "Initializing Plan Management System indexes..." }

        runBlocking {
            try {
                planRepository.initializeIndexes()
                log.info { "Plan indexes initialized successfully (plan_vector, plan_fulltext, step_vector)" }
            } catch (e: Exception) {
                log.warn(e) { "Failed to initialize plan indexes: ${e.message}" }
            }
        }
    }
}
