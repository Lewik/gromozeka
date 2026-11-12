plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
    jvm()
    android {
        namespace = "com.gromozeka.updates"
        compileSdk = 37
        minSdk = 24
        androidResources.enable = true
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":remote-protocol"))
            api(project(":domain"))
            api(libs.ktor.client.core)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain { kotlin.srcDir("src/javaMain/kotlin") }
        androidMain {
            kotlin.srcDir("src/javaMain/kotlin")
            dependencies { implementation(libs.kotlinx.coroutines.android) }
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
