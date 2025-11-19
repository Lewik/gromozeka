plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
}

val javaVersion = libs.versions.java.get().toInt()

kotlin {
    jvmToolchain(javaVersion)
    jvm {}
    
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":shared"))
                
                // SQL + Exposed
                implementation(libs.sqlite.jdbc)
                implementation(libs.exposed.core)
                implementation(libs.exposed.dao)
                implementation(libs.exposed.jdbc)
                implementation(libs.exposed.kotlin.datetime)
                implementation(libs.exposed.migration.core)
                implementation(libs.exposed.migration.jdbc)
                implementation(libs.flyway.core)
                
                // Vector Store
                implementation(libs.spring.ai.qdrant.store)
                
                // Knowledge Graph
                implementation(libs.neo4j.driver)
                
                // Spring AI for embeddings and chat models
                implementation(libs.spring.ai.openai)
                implementation(libs.spring.ai.anthropic)
                implementation(libs.spring.ai.google.genai)
                
                // Spring Boot
                implementation(libs.spring.boot.starter)
                implementation(libs.kotlinx.coroutines.reactor)
                implementation(libs.reactor.kotlin.extensions)
                
                // Serialization
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.jackson.module.kotlin)
                
                // Logging
                implementation(libs.klog)
                
                // Ktor Client for RerankService
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
            }
        }
    }
}
