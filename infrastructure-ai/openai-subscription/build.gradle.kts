plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
}

val javaVersion = libs.versions.java.get().toInt()

kotlin {
    jvmToolchain(javaVersion)

    jvm {}

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":infrastructure-ai"))

                implementation(libs.spring.boot.starter)
                implementation(libs.klog)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(project(":domain"))
                implementation(libs.kotlinx.datetime)
                implementation(kotlin("test"))
            }
        }
    }
}
