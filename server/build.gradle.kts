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

val javaVersion = libs.versions.java.get().toInt()

kotlin {
    jvmToolchain(javaVersion)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.ai.bom.get().toString())
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":remote-protocol"))
    implementation(project(":application"))
    implementation(project(":infrastructure-db"))
    implementation(project(":infrastructure-ai"))
    implementation(project(":infrastructure-ai:openai-subscription"))

    implementation(libs.spring.boot.starter)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.klog)
}

sourceSets {
    main {
        resources.srcDir(rootProject.file("presentation/src/jvmMain/resources"))
    }
}

application {
    mainClass.set("com.gromozeka.server.GromozekaServerMainKt")
    applicationDefaultJvmArgs = listOf(
        "-Dgromozeka.project.root=${rootProject.projectDir.absolutePath}"
    )
}
