package com.gromozeka.mobile.worker

import android.app.Application
import com.gromozeka.shared.logging.AndroidDiagnosticLogging

class GromozekaMobileWorkerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidDiagnosticLogging.install(this, "mobile-worker.log")
    }
}
