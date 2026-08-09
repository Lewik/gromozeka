package com.gromozeka.mobile.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gromozeka.domain.model.MobileWorkerAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MobileWorkerLocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val location = AndroidMobileWorkerSensors.locationFrom(intent) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val runtime = AndroidMobileWorkerRuntimeFactory.create(context)
            try {
                try {
                    AndroidMobileWorkerSensors.run { location.record(runtime) }
                    runtime.synchronize(MobileWorkerAppState.BACKGROUND)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.w(LOG_TAG, "Failed to store location event", error)
                }
            } finally {
                runtime.close()
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val LOG_TAG = "GromozekaMobileWorker"
    }
}
