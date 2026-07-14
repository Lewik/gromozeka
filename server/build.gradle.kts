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

tasks.named("processTestResources") {
    doNotTrackState("LongMemEval record-missing test runs update LLM cassette test resources while tests execute.")
}

tasks.withType<Test> {
    useJUnitPlatform()

    val isLongMemEvalRun = providers.systemProperty("gromozeka.longmemeval")
        .map { it == "true" }
        .orElse(false)
        .get()
    val isMemoryE2eRun = providers.systemProperty("gromozeka.memory.e2e")
        .map { it == "true" }
        .orElse(false)
        .get()
    if (isLongMemEvalRun || isMemoryE2eRun) {
        notCompatibleWithConfigurationCache("Memory real-model tests consume run-specific system properties and write cassette resources.")
        outputs.upToDateWhen { false }
    }
    if (isLongMemEvalRun) {
        reports.junitXml.required.set(false)
        reports.html.required.set(false)
    }

    fun passSystemProperty(name: String) {
        providers.systemProperty(name).orNull?.let { value ->
            systemProperty(name, value)
        }
    }

    passSystemProperty("gromozeka.memory.e2e")
    passSystemProperty("gromozeka.memory.e2e.subscriptionConfig")
    passSystemProperty("gromozeka.memory.e2e.caseFilter")
    passSystemProperty("gromozeka.memory.e2e.modelName")
    passSystemProperty("gromozeka.memory.e2e.websocketResponseTimeoutMs")
    passSystemProperty("gromozeka.memory.e2e.websocketTransportTimeoutMs")
    passSystemProperty("gromozeka.memory.e2e.turnCompletionTimeoutMs")
    passSystemProperty("gromozeka.memory.e2e.memoryLlmStageTimeoutMs")

    passSystemProperty("gromozeka.longmemeval")
    passSystemProperty("gromozeka.longmemeval.data")
    passSystemProperty("gromozeka.longmemeval.limit")
    passSystemProperty("gromozeka.longmemeval.offset")
    passSystemProperty("gromozeka.longmemeval.caseFilter")
    passSystemProperty("gromozeka.longmemeval.type")
    passSystemProperty("gromozeka.longmemeval.sample")
    passSystemProperty("gromozeka.longmemeval.modelName")
    passSystemProperty("gromozeka.longmemeval.readSearchReasoningEffort")
    passSystemProperty("gromozeka.longmemeval.subscriptionConfig")
    passSystemProperty("gromozeka.longmemeval.websocketResponseTimeoutMs")
    passSystemProperty("gromozeka.longmemeval.websocketTransportTimeoutMs")
    passSystemProperty("gromozeka.longmemeval.evalLlmTimeoutMs")
    passSystemProperty("gromozeka.longmemeval.evalLlmMaxAttempts")
    passSystemProperty("gromozeka.longmemeval.memoryLlmStageTimeoutMs")
    passSystemProperty("gromozeka.longmemeval.memoryLlmMaxAttempts")
    passSystemProperty("gromozeka.longmemeval.memoryParallelism")

    passSystemProperty("gromozeka.ai.openai-subscription.websocket-response-timeout-ms")
    passSystemProperty("gromozeka.ai.openai-subscription.websocket-transport-timeout-ms")
    passSystemProperty("gromozeka.memory.llm.timeoutMs")

    passSystemProperty("gromozeka.llm.cassette.mode")
    passSystemProperty("gromozeka.llm.cassette.dir")
    passSystemProperty("gromozeka.llm.cassette.reportUnused")
    passSystemProperty("gromozeka.llm.cassette.deleteUnused")
    passSystemProperty("gromozeka.llm.cassette.writeMissDebug")
}
