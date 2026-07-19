plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
}

val javaVersion = libs.versions.java.get().toInt()

kotlin {
    jvmToolchain(javaVersion)
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(project(":domain"))
    implementation(libs.spring.boot.starter.amqp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.klog)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(kotlin("test"))
}
