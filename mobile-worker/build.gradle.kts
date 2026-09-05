plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

val javaVersion = libs.versions.java.get().toInt()
val nativePackageVersion = rootProject.version.toString()
    .substringBefore('-')
    .substringBefore('+')
    .let { version -> if (version == "0.0.0") "1.0.0" else version }

kotlin {
    jvmToolchain(javaVersion)
    applyDefaultHierarchyTemplate()
    jvm()

    android {
        namespace = "com.gromozeka.mobile.worker"
        compileSdk = 37
        minSdk = 26
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "GromozekaMobileWorker"
            binaryOption("bundleId", "com.gromozeka.mobile.worker")
            binaryOption("bundleShortVersionString", nativePackageVersion)
            binaryOption("bundleVersion", nativePackageVersion)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":remote-protocol"))
                implementation(project(":worker-runtime"))
                implementation(compose.runtime)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.car.app)
                implementation(libs.androidx.health.connect.client)
                implementation(compose.foundation)
                implementation(compose.material3)
            }
        }
        val iosMain by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
    }
}
