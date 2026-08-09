package com.gromozeka.mobile.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.car.app.connection.CarConnection
import com.gromozeka.domain.model.MobileWorkerAppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MobileWorkerCarConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CarConnection.ACTION_CAR_CONNECTION_UPDATED) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val runtime = AndroidMobileWorkerRuntimeFactory.create(context.applicationContext)
            try {
                if (runtime.status().enrolled && AndroidAutoSignals.capture(context, runtime)) {
                    runtime.synchronize(MobileWorkerAppState.BACKGROUND)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(LOG_TAG, "Failed to store Android Auto connection event", error)
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
