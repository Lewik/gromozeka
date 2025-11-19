package com.gromozeka.infrastructure.db.config

import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RepositoryConfiguration {

    @Bean
    fun repositoryJson(): Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
}
