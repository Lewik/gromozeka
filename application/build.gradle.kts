plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {}
    
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":domain"))  // Transitively provides :shared
                
                implementation(libs.spring.boot.starter)
                implementation("org.springframework:spring-tx:6.2.2")
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.klog)
                
                // kotlinx.serialization для JSON парсинга
                implementation(libs.kotlinx.serialization.json)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
