plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

val javaVersion = libs.versions.java.get().toInt()

kotlin {
    jvmToolchain(javaVersion)
}

dependencies {
    api(project(":domain"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
}
