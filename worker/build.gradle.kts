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
    implementation(project(":remote-protocol"))
    implementation(project(":application"))
    implementation(project(":infrastructure-ai"))
    implementation(project(":infrastructure-ai:openai-subscription"))

    implementation(libs.spring.boot.starter)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.klog)
    implementation(libs.jna)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.gromozeka.worker.GromozekaWorkerMainKt")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("gromozeka-worker.jar")
    manifest {
        attributes["Implementation-Version"] = project.version
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Implementation-Version"] = project.version
    }
}

tasks.withType<JavaExec>().matching { it.name == "run" || it.name == "bootRun" }.configureEach {
    systemProperty("gromozeka.project.root", rootProject.projectDir.absolutePath)
    environment(
        "GROMOZEKA_BROWSER_MCP_LAUNCHER",
        rootProject.layout.projectDirectory.file("deploy/distribution/gromozeka-browser-mcp").asFile.absolutePath,
    )
    environment(
        "GROMOZEKA_BROWSER_MCP_HOME",
        rootProject.layout.projectDirectory.dir("browser-mcp").asFile.absolutePath,
    )
    environment(
        "GROMOZEKA_RUNTIME_BOOTSTRAP",
        rootProject.layout.projectDirectory.file("deploy/distribution/runtime-bootstrap.sh").asFile.absolutePath,
    )
}

distributions {
    named("boot") {
        distributionBaseName.set(project.name)
    }
}

listOf("startScripts", "installDist", "distZip", "distTar").forEach { taskName ->
    tasks.named(taskName) {
        enabled = false
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    providers.systemProperty("gromozeka.computer-use.live").orNull?.let { value ->
        systemProperty("gromozeka.computer-use.live", value)
    }
}
