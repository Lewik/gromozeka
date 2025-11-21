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
                implementation(project(":domain"))  // Transitively provides :shared
                
                // Spring AI providers
                implementation(libs.spring.ai.openai)
                implementation(libs.spring.ai.anthropic)
                implementation(libs.spring.ai.google.genai)
                implementation(libs.spring.ai.ollama)
                implementation(libs.spring.ai.starter.model.openai)
                
                // Reactor Kotlin extensions for Flux/Flow conversion
                implementation(libs.reactor.kotlin.extensions)
                
                // Spring Boot
                implementation(libs.spring.boot.starter)
                
                // klog - Kotlin logging framework
                implementation(libs.klog)
                
                implementation(libs.kotlin.reflect)
                implementation(libs.kotlinx.coroutines.reactor)
                implementation(libs.kotlinx.datetime)
                
                // Ktor Client for MCP
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.auth)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                
                // Ktor Server for MCP
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.sse)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.xmlutil.serialization)
                implementation(libs.jackson.module.kotlin)

                // MCP SDK for Gromozeka MCP tools (coexists with Java MCP SDK from Spring AI)
                implementation(libs.mcp.kotlin.sdk)
                implementation(libs.mcp.java.sdk.core)
            }
        }
    }
}
