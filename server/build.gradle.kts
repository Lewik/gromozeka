import java.net.URI

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

dependencies {
    implementation(project(":domain"))
    implementation(project(":remote-protocol"))
    implementation(project(":application"))
    implementation(project(":infrastructure-db"))
    implementation(project(":infrastructure-ai"))
    implementation(project(":infrastructure-ai:openai-subscription"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.amqp)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.websockets)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mcp.kotlin.sdk)
    implementation(libs.klog)

    testImplementation(project(":remote-client"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotlinx.datetime)
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.gromozeka.server.GromozekaServerMainKt")
    applicationDefaultJvmArgs = listOf(
        "-Dgromozeka.project.root=${rootProject.projectDir.absolutePath}"
    )
}

val longMemEvalDataFiles = mapOf(
    "longmemeval_oracle.json" to
        "https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_oracle.json",
    "longmemeval_s_cleaned.json" to
        "https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_s_cleaned.json",
    "longmemeval_m_cleaned.json" to
        "https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_m_cleaned.json",
)

tasks.register("downloadLongMemEvalData") {
    group = "verification"
    description = "Downloads official LongMemEval cleaned JSON files into .sources/longmemeval/data."

    doLast {
        val dataDirectory = rootProject.layout.projectDirectory.dir(".sources/longmemeval/data").asFile
        dataDirectory.mkdirs()
        longMemEvalDataFiles.forEach { (fileName, url) ->
            val target = dataDirectory.resolve(fileName)
            if (target.exists()) {
                logger.lifecycle("LongMemEval data already exists: ${target.absolutePath}")
            } else {
                logger.lifecycle("Downloading $fileName")
                URI(url).toURL().openStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    if (System.getProperty("gromozeka.longmemeval") == "true") {
        reports.junitXml.required.set(false)
    }

    System.getProperty("gromozeka.memory.e2e")?.let {
        systemProperty("gromozeka.memory.e2e", it)
    }
    System.getProperty("gromozeka.memory.e2e.subscriptionConfig")?.let {
        systemProperty("gromozeka.memory.e2e.subscriptionConfig", it)
    }
    System.getProperty("gromozeka.memory.e2e.caseFilter")?.let {
        systemProperty("gromozeka.memory.e2e.caseFilter", it)
    }
    System.getProperty("gromozeka.memory.e2e.modelName")?.let {
        systemProperty("gromozeka.memory.e2e.modelName", it)
    }
    System.getProperty("gromozeka.memory.e2e.websocketResponseTimeoutMs")?.let {
        systemProperty("gromozeka.memory.e2e.websocketResponseTimeoutMs", it)
    }
    System.getProperty("gromozeka.memory.e2e.websocketTransportTimeoutMs")?.let {
        systemProperty("gromozeka.memory.e2e.websocketTransportTimeoutMs", it)
    }
    System.getProperty("gromozeka.memory.e2e.turnCompletionTimeoutMs")?.let {
        systemProperty("gromozeka.memory.e2e.turnCompletionTimeoutMs", it)
    }
    System.getProperty("gromozeka.memory.e2e.memoryLlmStageTimeoutMs")?.let {
        systemProperty("gromozeka.memory.e2e.memoryLlmStageTimeoutMs", it)
    }
    System.getProperty("gromozeka.longmemeval")?.let {
        systemProperty("gromozeka.longmemeval", it)
    }
    System.getProperty("gromozeka.longmemeval.data")?.let {
        systemProperty("gromozeka.longmemeval.data", it)
    }
    System.getProperty("gromozeka.longmemeval.limit")?.let {
        systemProperty("gromozeka.longmemeval.limit", it)
    }
    System.getProperty("gromozeka.longmemeval.offset")?.let {
        systemProperty("gromozeka.longmemeval.offset", it)
    }
    System.getProperty("gromozeka.longmemeval.caseFilter")?.let {
        systemProperty("gromozeka.longmemeval.caseFilter", it)
    }
    System.getProperty("gromozeka.longmemeval.type")?.let {
        systemProperty("gromozeka.longmemeval.type", it)
    }
    System.getProperty("gromozeka.longmemeval.sample")?.let {
        systemProperty("gromozeka.longmemeval.sample", it)
    }
    System.getProperty("gromozeka.longmemeval.modelName")?.let {
        systemProperty("gromozeka.longmemeval.modelName", it)
    }
    System.getProperty("gromozeka.longmemeval.subscriptionConfig")?.let {
        systemProperty("gromozeka.longmemeval.subscriptionConfig", it)
    }
    System.getProperty("gromozeka.longmemeval.websocketResponseTimeoutMs")?.let {
        systemProperty("gromozeka.longmemeval.websocketResponseTimeoutMs", it)
    }
    System.getProperty("gromozeka.longmemeval.websocketTransportTimeoutMs")?.let {
        systemProperty("gromozeka.longmemeval.websocketTransportTimeoutMs", it)
    }
    System.getProperty("gromozeka.longmemeval.memoryLlmStageTimeoutMs")?.let {
        systemProperty("gromozeka.longmemeval.memoryLlmStageTimeoutMs", it)
    }
    System.getProperty("gromozeka.longmemeval.memoryLlmMaxAttempts")?.let {
        systemProperty("gromozeka.longmemeval.memoryLlmMaxAttempts", it)
    }
    System.getProperty("gromozeka.ai.openai-subscription.websocket-response-timeout-ms")?.let {
        systemProperty("gromozeka.ai.openai-subscription.websocket-response-timeout-ms", it)
    }
    System.getProperty("gromozeka.ai.openai-subscription.websocket-transport-timeout-ms")?.let {
        systemProperty("gromozeka.ai.openai-subscription.websocket-transport-timeout-ms", it)
    }
    System.getProperty("gromozeka.memory.llm.timeoutMs")?.let {
        systemProperty("gromozeka.memory.llm.timeoutMs", it)
    }
    System.getProperty("gromozeka.llm.cassette.mode")?.let {
        systemProperty("gromozeka.llm.cassette.mode", it)
    }
    System.getProperty("gromozeka.llm.cassette.dir")?.let {
        systemProperty("gromozeka.llm.cassette.dir", it)
    }
    System.getProperty("gromozeka.llm.cassette.reportUnused")?.let {
        systemProperty("gromozeka.llm.cassette.reportUnused", it)
    }
    System.getProperty("gromozeka.llm.cassette.deleteUnused")?.let {
        systemProperty("gromozeka.llm.cassette.deleteUnused", it)
    }
    System.getProperty("gromozeka.llm.cassette.writeMissDebug")?.let {
        systemProperty("gromozeka.llm.cassette.writeMissDebug", it)
    }
}
