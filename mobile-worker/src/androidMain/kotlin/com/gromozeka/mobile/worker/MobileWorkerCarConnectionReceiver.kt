package com.gromozeka.mobile.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.car.app.connection.CarConnection
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
                if (runtime.status().enrolled) {
                    AndroidAutoSignals.capture(context, runtime)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                androidMobileWorkerLog.warn(error, "Failed to store Android Auto connection event")
            } finally {
                runtime.close()
                pendingResult.finish()
            }
        }
    }
}
