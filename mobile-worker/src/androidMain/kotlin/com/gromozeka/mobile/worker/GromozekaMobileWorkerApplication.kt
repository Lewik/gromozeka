package com.gromozeka.mobile.worker

import android.app.Application
import com.gromozeka.shared.logging.AndroidDiagnosticLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GromozekaMobileWorkerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.gromozeka.updates.AndroidUpdateVisibility.register(this)
        AndroidWorkerApplicationUpdates.start(this)
        AndroidDiagnosticLogging.install(this, "mobile-worker.log")
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { AndroidWorkerSoundOutput.recoverVolume(applicationContext) }
                .onFailure { androidMobileWorkerLog.error { "Could not restore interrupted alarm volume: ${it::class.simpleName}" } }
        }
    }
}
