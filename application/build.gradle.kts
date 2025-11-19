plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.spring)
}

kotlin {
    jvm {}
    
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":shared"))
                implementation(project(":infrastructure-ai"))
                
                implementation(libs.spring.boot.starter)
                implementation("org.springframework:spring-tx:6.2.2")
                implementation(libs.kotlinx.datetime)
                implementation(libs.klog)
                
                // Spring AI для MessageConversionService, MessageSquashService
                implementation(libs.spring.ai.openai)
                
                // kotlinx.serialization для JSON парсинга
                implementation(libs.kotlinx.serialization.json)
                
                // Reactor для MessageSquashService
                implementation(libs.kotlinx.coroutines.reactor)
            }
        }
    }
}
