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
                implementation(project(":infrastructure-db"))  // For embedding cache

                implementation(libs.openai.java)
                implementation(libs.anthropic.java)
                implementation(libs.anthropic.java.bedrock)
                implementation(libs.aws.sdk.sso)
                implementation(libs.aws.sdk.ssooidc)
                // Spring Boot
                implementation(libs.spring.boot.starter)
                
                // klog - Kotlin logging framework
                implementation(libs.klog)
                
                implementation(libs.kotlin.reflect)
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

                // MCP SDK for Gromozeka MCP tools
                implementation(libs.mcp.kotlin.sdk)
                
                // Tree-sitter for code analysis
                implementation(libs.ktreesitter)
                implementation(project(":infrastructure-ai:tree-sitter-kotlin-grammar"))

                // LSP4J for Language Server Protocol integration
                implementation(libs.lsp4j)
            }
        }
        
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
