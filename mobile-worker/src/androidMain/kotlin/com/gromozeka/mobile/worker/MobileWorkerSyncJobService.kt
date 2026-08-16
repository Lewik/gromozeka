package com.gromozeka.mobile.worker

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.gromozeka.domain.model.MobileWorkerAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MobileWorkerSyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            val runtime = AndroidMobileWorkerRuntimeFactory.create(applicationContext)
            val sensors = AndroidMobileWorkerSensors(applicationContext)
            try {
                if (!runtime.status().enrolled) {
                    cancel(applicationContext)
                } else {
                    sensors.battery()?.let {
                        runtime.recordBattery(it.levelPercent, it.charging, it.lowPowerMode)
                    }
                    runtime.recordAirplaneMode(sensors.airplaneMode())
                    sensors.bluetoothEnabled()?.let { runtime.recordBluetoothPower(it) }
                    AndroidAutoSignals.capture(applicationContext, runtime)
                    sensors.captureConfiguredState(runtime)
                    AndroidSleepSignals(applicationContext).captureLatestSession(runtime)
                    sensors.enableSignificantLocationUpdates()
                    sensors.enableBlePresenceUpdates()
                    runtime.synchronize(MobileWorkerAppState.BACKGROUND, heartbeatWhenIdle = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                androidMobileWorkerLog.warn(error, "Scheduled Mobile Worker synchronization failed")
            } finally {
                runtime.close()
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        running = null
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, MobileWorkerSyncJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setPeriodic(15 * 60 * 1_000L)
                    .build()
            )
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }

        private const val JOB_ID = 27_042
    }
}
