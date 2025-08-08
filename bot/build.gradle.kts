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
                implementation(compose.material3)
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
                
                implementation(libs.jnativehook)
                
                // Batik for logo generation task
                implementation(libs.batik.transcoder)
                implementation(libs.batik.codec)
            }
        }
        
        val jvmTest by getting {
            dependencies {
                implementation(libs.spring.boot.starter.test)
                implementation(libs.junit.jupiter)
                implementation(libs.junit.platform.launcher)
                implementation(libs.mockk)
                implementation(libs.kotest.runner.junit5)
                implementation(libs.kotest.assertions.core)
                implementation(libs.kotest.assertions.json)
                implementation(libs.kotlinx.serialization.json)
                
                // Batik for SVG to PNG conversion (build-time only)
                implementation(libs.batik.transcoder)
                implementation(libs.batik.codec)
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
        jvmArgs += listOf(
            "-Xdock:icon=src/jvmMain/resources/logos/logo-256x256.png",
            "-Xdock:name=Gromozeka"
        )
    }
}

tasks.register<Test>("convertLogo") {
    description = "Run only the logo generation test"
    group = "build"
    
    useJUnitPlatform()
    filter {
        includeTestsMatching("com.gromozeka.bot.LogoGenerationTest.generateLogos")
    }
    
    testClassesDirs = kotlin.jvm().compilations["test"].output.classesDirs
    classpath = kotlin.jvm().compilations["test"].runtimeDependencyFiles
    
    inputs.file("src/jvmMain/resources/logo.svg")
    outputs.dir("src/jvmMain/resources/logos")
}
