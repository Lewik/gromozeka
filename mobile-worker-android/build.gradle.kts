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
}

dependencies {
    implementation(project(":mobile-worker"))
}
