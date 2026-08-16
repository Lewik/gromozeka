package com.gromozeka.mobile.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MobileWorkerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val applicationContext = context.applicationContext
            val runtime = AndroidMobileWorkerRuntimeFactory.create(applicationContext)
            try {
                if (runtime.status().enrolled) {
                    val sensors = AndroidMobileWorkerSensors(applicationContext)
                    sensors.enableSignificantLocationUpdates()
                    sensors.enableBlePresenceUpdates()
                    MobileWorkerSyncJobService.schedule(applicationContext)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                androidMobileWorkerLog.warn(error, "Failed to restore Mobile Worker signals")
            } finally {
                runtime.close()
                pendingResult.finish()
            }
        }
    }
}
