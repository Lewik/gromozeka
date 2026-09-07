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
    implementation(project(":worker-runtime"))
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

@Suppress("UNCHECKED_CAST")
val localDevelopmentEnvironment =
    rootProject.extensions.extraProperties["gromozekaLocalDevelopmentEnvironment"]
        as Map<String, String>
val localDevelopmentHome = rootProject.layout.projectDirectory
    .dir("dev-data/client/.gromozeka")
    .asFile.absolutePath
val localWorkerConfig = rootProject.layout.projectDirectory
    .file("dev-data/client/.gromozeka/worker-dev.yaml")
    .asFile.absolutePath

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
        "GROMOZEKA_NODE_EXECUTABLE",
        "node",
    )
    if (localDevelopmentEnvironment.isNotEmpty()) {
        fun localValue(name: String): String =
            System.getenv(name)?.takeIf(String::isNotBlank)
                ?: localDevelopmentEnvironment.getValue(name)

        val workerConfig = System.getenv("GROMOZEKA_WORKER_CONFIG")
            ?: localWorkerConfig
        environment("GROMOZEKA_DEV_SLOT", localValue("GROMOZEKA_DEV_SLOT"))
        environment("GROMOZEKA_REMOTE_PORT", localValue("GROMOZEKA_REMOTE_PORT"))
        environment("GROMOZEKA_POSTGRES_PORT", localValue("GROMOZEKA_POSTGRES_PORT"))
        environment("GROMOZEKA_MODE", System.getenv("GROMOZEKA_MODE") ?: "dev")
        environment(
            "GROMOZEKA_HOME",
            System.getenv("GROMOZEKA_HOME")
                ?: localDevelopmentHome,
        )
        environment("GROMOZEKA_WORKER_CONFIG", workerConfig)
        environment(
            "SPRING_CONFIG_ADDITIONAL_LOCATION",
            System.getenv("SPRING_CONFIG_ADDITIONAL_LOCATION") ?: "file:$workerConfig",
        )
        environment(
            "GROMOZEKA_SERVER_URL",
            System.getenv("GROMOZEKA_SERVER_URL")
                ?: "http://127.0.0.1:${localValue("GROMOZEKA_REMOTE_PORT")}",
        )
    }
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
