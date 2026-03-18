package com.gromozeka.infrastructure.ai.config

import com.fasterxml.jackson.databind.Module
import com.gromozeka.infrastructure.ai.serialization.DomainJacksonModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Jackson configuration for Spring AI tool serialization.
 * 
 * Registers custom serializers/deserializers for domain types
 * to keep domain layer clean from Jackson dependencies.
 * 
 * Spring Boot automatically picks up all Module beans and registers them
 * with the global ObjectMapper used by Spring AI.
 */
@Configuration
class JacksonConfig {
    
    /**
     * Register domain Jackson module.
     * 
     * Provides serializers for:
     * - Step.Type enum (COMMAND → "command", case-insensitive deserialization)
     * 
     * Spring Boot auto-configures this into ObjectMapper.
     */
    @Bean
    fun domainJacksonModule(): Module {
        return DomainJacksonModule()
    }
}
