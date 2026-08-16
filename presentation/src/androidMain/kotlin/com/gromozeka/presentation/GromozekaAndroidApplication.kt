package com.gromozeka.presentation

import android.app.Application
import com.gromozeka.shared.logging.AndroidDiagnosticLogging

class GromozekaAndroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidDiagnosticLogging.install(this, "client.log")
    }
}
