plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.gromozeka.mobile.worker.android"
    compileSdk = 37
    enableKotlin = false

    defaultConfig {
        applicationId = "com.gromozeka.mobile.worker"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = rootProject.version.toString()
        testInstrumentationRunner = "com.gromozeka.mobile.worker.GatewaySmokeInstrumentation"
    }

    providers.gradleProperty("workerLifecycleResources").orNull?.let { resources ->
        buildTypes.create("lifecycle") {
            initWith(buildTypes.getByName("debug"))
            applicationIdSuffix = ".lifecycle"
            matchingFallbacks += "debug"
        }
        sourceSets.getByName("lifecycle").res.srcDir(resources)
        testBuildType = "lifecycle"
    }
}

dependencies {
    implementation(project(":mobile-worker"))
}
