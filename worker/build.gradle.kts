plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    application
}

extra["kotlin.version"] = libs.versions.kotlin.get()
extra["kotlin-serialization.version"] = libs.versions.kotlinx.serialization.get()
extra["kotlin-coroutines.version"] = libs.versions.kotlinx.coroutines.get()

val javaVersion = libs.versions.java.get().toInt()

kotlin {
    jvmToolchain(javaVersion)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":infrastructure-db"))
    implementation(project(":infrastructure-runtime"))
    implementation(project(":infrastructure-ai"))
    implementation(project(":infrastructure-ai:openai-subscription"))

    implementation(libs.spring.boot.starter)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.klog)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.gromozeka.worker.GromozekaWorkerMainKt")
    applicationDefaultJvmArgs = listOf(
        "-Dgromozeka.project.root=${rootProject.projectDir.absolutePath}"
    )
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("gromozeka-worker.jar")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
