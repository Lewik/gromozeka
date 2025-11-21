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
    }
}
