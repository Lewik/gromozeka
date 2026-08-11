package com.gromozeka.application.service

import com.gromozeka.domain.repository.RuntimeCatalogBootstrapRepository
import jakarta.annotation.PostConstruct
import klog.KLoggers
import kotlinx.coroutines.runBlocking
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service

@Service
@DependsOn("database")
class RuntimeCatalogBootstrapService(
    private val templates: RuntimeCatalogTemplateApplicationService,
    private val bootstrapRepository: RuntimeCatalogBootstrapRepository,
) {
    private val log = KLoggers.logger(this)

    @PostConstruct
    fun initialize() {
        val result = runBlocking {
            val seed = templates.createSeed()
            BootstrapResult(
                initialized = bootstrapRepository.initializeIfEmpty(seed),
                repaired = bootstrapRepository.insertMissingSeedEntries(seed),
            )
        }
        if (result.initialized) {
            log.info { "Initialized runtime configuration catalog from application templates" }
        }
        if (result.repaired) {
            log.info { "Inserted missing runtime configuration catalog seed entries" }
        }
    }

    private data class BootstrapResult(
        val initialized: Boolean,
        val repaired: Boolean,
    )
}
