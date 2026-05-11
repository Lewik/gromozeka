plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

val javaVersion = libs.versions.java.get().toInt()

kotlin {
    jvmToolchain(javaVersion)
    
    jvm {}
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
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

android {
    namespace = "com.gromozeka.domain"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
