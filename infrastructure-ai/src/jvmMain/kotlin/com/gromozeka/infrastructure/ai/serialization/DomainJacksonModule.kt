package com.gromozeka.infrastructure.ai.serialization

import com.fasterxml.jackson.databind.module.SimpleModule
import com.gromozeka.domain.model.Step

/**
 * Jackson module for domain model serialization.
 * 
 * Provides custom serializers/deserializers for domain types
 * to keep domain layer clean from Jackson dependencies.
 * 
 * Registered in Spring context via @Bean in JacksonConfig.
 */
class DomainJacksonModule : SimpleModule() {
    init {
        // Step.Type enum serialization
        addSerializer(Step.Type::class.java, StepTypeSerializer())
        addDeserializer(Step.Type::class.java, StepTypeDeserializer())
    }
}
