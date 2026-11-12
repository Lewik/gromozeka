plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.gromozeka.mobile.worker.android"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    enableKotlin = false

    val releaseKeystore = providers.environmentVariable("GROMOZEKA_ANDROID_KEYSTORE").orNull
    if (releaseKeystore != null) {
        signingConfigs.create("distribution") {
            storeFile = file(releaseKeystore)
            storePassword = providers.environmentVariable("GROMOZEKA_ANDROID_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("GROMOZEKA_ANDROID_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("GROMOZEKA_ANDROID_KEY_PASSWORD").get()
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("distribution")
    }

    defaultConfig {
        applicationId = "com.gromozeka.mobile.worker"
        minSdk = 26
        targetSdk = 35
        val parts = rootProject.version.toString().substringBefore('-').split('.').map(String::toInt)
        require(parts.size == 3 && parts[0] in 0..2000 && parts[1] in 0..999 && parts[2] in 0..999)
        versionCode = (parts[0] * 1_000_000 + parts[1] * 1000 + parts[2]).coerceAtLeast(1)
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
