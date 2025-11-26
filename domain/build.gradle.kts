plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

val javaVersion = libs.versions.java.get().toInt()

kotlin {
    jvmToolchain(javaVersion)
    
    jvm {}
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":shared"))  // Transitive dependency for all domain consumers
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        
        val jvmMain by getting {
            dependencies {
                // Spring AI types for JVM-specific service interfaces
                // Using openai module which brings spring-ai-core transitively
                implementation(libs.spring.ai.openai)
            }
        }
    }
}
