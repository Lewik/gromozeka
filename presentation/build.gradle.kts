import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.jetbrains.compose)
}

// Force Kotlin version override for Spring Boot 3.4.4 (uses 1.9.25 by default)
extra["kotlin.version"] = libs.versions.kotlin.get()

// Override kotlinx-serialization version from Spring Boot BOM (1.6.3 -> 1.9.0)
extra["kotlin-serialization.version"] = libs.versions.kotlinx.serialization.get()

val javaVersion = libs.versions.java.get().toInt()

kotlin {
    jvmToolchain(javaVersion)

    jvm {}
    
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(libs.spring.boot.starter)
                
                // Dependencies on other modules
                implementation(project(":domain"))
                implementation(project(":application"))
                implementation(project(":shared"))
                implementation(project(":infrastructure-db"))
                implementation(project(":infrastructure-ai"))

                // klog - Kotlin logging framework
                implementation(libs.klog)
                
                // FileKit for file picker
                implementation(libs.filekit.core)
                implementation(libs.filekit.compose)
                
                // Markdown renderer
                implementation(libs.multiplatform.markdown.renderer.m3)
                implementation(libs.multiplatform.markdown.renderer.code)

                implementation(libs.kotlin.reflect)
                implementation(libs.kotlinx.coroutines.reactor)
                implementation(libs.kotlinx.datetime)
                
                // Spring AI providers
                implementation(libs.spring.ai.openai)
                implementation(libs.spring.ai.starter.model.openai)
                implementation(libs.spring.ai.anthropic)
                implementation(libs.spring.ai.google.genai)
                implementation(libs.spring.ai.ollama)
                
                // MCP SDK for internal tools
                implementation(libs.mcp.kotlin.sdk)
                
                // Flyway (for DatabaseBackupCallback)
                implementation(libs.flyway.core)
                
                // Ktor Client
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.auth)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.xmlutil.serialization)
                implementation(libs.jackson.module.kotlin)

                // Age encryption for secure log packaging
                implementation(libs.jagged.api)
                implementation(libs.jagged.x25519)
                implementation(libs.jagged.framework)
                
                // Batik for logo generation task
                implementation(libs.batik.transcoder)
                implementation(libs.batik.codec)

                // Apache PDFBox for PDF parsing
                implementation(libs.pdfbox)
            }
        }
        
        val jvmTest by getting {
            dependencies {
                implementation(libs.spring.boot.starter.test)
                implementation(libs.mockk)
                implementation(libs.kotlinx.serialization.json)
                implementation(kotlin("test"))
                
                // Batik for SVG to PNG conversion (build-time only)
                implementation(libs.batik.transcoder)
                implementation(libs.batik.codec)
            }
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
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

compose.desktop {
    application {
        mainClass = "com.gromozeka.presentation.MainKt"
        
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Gromozeka"
            packageVersion = rootProject.version.toString()
            
            macOS {
                iconFile.set(project.file("../bot/src/jvmMain/resources/logos/gromozeka-app-icon.icns"))
                bundleID = "com.gromozeka.app"
            }
            
            windows {
                iconFile.set(project.file("../bot/src/jvmMain/resources/logos/gromozeka-app-icon.ico"))
            }
            
            linux {
                iconFile.set(project.file("../bot/src/jvmMain/resources/logos/gromozeka-app-icon.png"))
            }
        }
    }
}
