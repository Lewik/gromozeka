package com.gromozeka.bot.config

import klog.KLoggers
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.pgvector.PgVectorStore
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

@Configuration
class VectorMemoryConfig {

    private val log = KLoggers.logger(this)

    @Bean
    @Qualifier("postgresJdbcTemplate")
    fun postgresJdbcTemplate(
        @Qualifier("postgresDataSource") dataSource: DataSource?
    ): JdbcTemplate? {
        if (dataSource == null) {
            log.debug("PostgreSQL DataSource not available, JdbcTemplate will not be created")
            return null
        }

        return try {
            JdbcTemplate(dataSource).also {
                log.debug("PostgreSQL JdbcTemplate created successfully")
            }
        } catch (e: Exception) {
            log.warn("Failed to create PostgreSQL JdbcTemplate: ${e.message}")
            null
        }
    }

    @Bean
    fun pgVectorStore(
        @Qualifier("postgresJdbcTemplate") jdbcTemplate: JdbcTemplate?,
        embeddingModel: EmbeddingModel
    ): VectorStore? {
        if (jdbcTemplate == null) {
            log.info("PostgreSQL JdbcTemplate not available, vector storage will be disabled")
            return null
        }

        return try {
            PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .schemaName("public")
                .vectorTableName("vector_store")
                .dimensions(1536)
                .initializeSchema(true)
                .build().also {
                    log.info("PgVectorStore initialized successfully for vector storage")
                }
        } catch (e: Exception) {
            log.warn("Failed to create PgVectorStore: ${e.message}. Vector storage will be unavailable.")
            null
        }
    }
}
