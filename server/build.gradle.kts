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

    testImplementation(project(":remote-client"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mongodb.driver.kotlin.coroutine)
    testImplementation(libs.kotlinx.datetime)
    testImplementation(kotlin("test"))
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

tasks.withType<Test> {
    useJUnitPlatform()

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
}
