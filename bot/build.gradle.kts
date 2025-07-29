plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(17)
    
    jvm {
        withJava()
    }
    
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(libs.spring.ai.openai)
                implementation(libs.spring.ai.anthropic)
                implementation(libs.spring.ai.starter.mcp.client)
                
                implementation(compose.desktop.currentOs)
                implementation(libs.spring.boot.starter)
                
                implementation(project(":shared"))
                
                implementation(libs.kotlin.reflect)
                implementation(libs.kotlinx.datetime)
                
                implementation(platform(libs.ktor.bom.get().toString()))
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.auth)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.jackson.module.kotlin)
                
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.sqldelight.coroutines.extensions)
                implementation(libs.sqldelight.primitive.adapters)
            }
        }
        
        val jvmTest by getting {
            dependencies {
                implementation(libs.spring.boot.starter.test)
                implementation(libs.junit.jupiter)
                implementation(libs.junit.platform.launcher)
            }
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.ai.bom.get().toString())
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

sqldelight {
    databases {
        create("ChatDatabase") {
            packageName.set("com.gromozeka.bot.db")
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.gromozeka.bot.ChatApplicationKt"
    }
}