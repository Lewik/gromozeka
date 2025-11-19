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
                
                implementation(libs.spring.boot.starter)
                implementation("org.springframework:spring-tx:6.2.2")
                implementation(libs.kotlinx.datetime)
                implementation(libs.klog)
            }
        }
    }
}
