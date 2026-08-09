package com.gromozeka.mobile.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log
import com.gromozeka.domain.model.GeofenceTransition
import com.gromozeka.domain.model.MobileWorkerAppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MobileWorkerGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val regionId = AndroidMobileWorkerSensors.geofenceRegionId(intent) ?: return
        if (!intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val runtime = AndroidMobileWorkerRuntimeFactory.create(context.applicationContext)
            try {
                runtime.recordGeofence(
                    regionId,
                    if (entering) GeofenceTransition.ENTERED else GeofenceTransition.EXITED,
                )
                runtime.synchronize(MobileWorkerAppState.BACKGROUND)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(LOG_TAG, "Failed to store geofence transition", error)
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
