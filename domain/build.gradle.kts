plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

val javaVersion = libs.versions.java.get().toInt()

kotlin {
    jvmToolchain(javaVersion)
    
    jvm {}
    wasmJs {
        browser()
    }
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":shared"))  // Transitive dependency for all domain consumers
                api(libs.kotlinx.datetime)
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.coroutines.core)  // For StateFlow, SharedFlow
            }
        }
        
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
