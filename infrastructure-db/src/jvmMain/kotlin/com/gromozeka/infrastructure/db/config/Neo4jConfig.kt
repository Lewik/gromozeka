package com.gromozeka.infrastructure.db.config

import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(name = ["knowledge-graph.enabled"], havingValue = "true", matchIfMissing = false)
class Neo4jConfig {

    @Bean
    fun neo4jDriver(): Driver {
        return GraphDatabase.driver(
            "bolt://localhost:7687",
            AuthTokens.basic("neo4j", "password")
        )
    }
}
