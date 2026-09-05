package com.gromozeka.mobile.worker

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobWorkItem
import android.content.Intent
import android.content.ComponentName
import android.content.Context
import com.gromozeka.domain.model.WorkerAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MobileWorkerSyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (params.jobId == IMMEDIATE_JOB_ID) {
                while (true) {
                    val item = params.dequeueWork() ?: break
                    if (synchronize(collectSensors = false)) scheduleRetry(applicationContext)
                    params.completeWork(item)
                }
            } else {
                val retry = synchronize(collectSensors = params.jobId == PERIODIC_JOB_ID)
                jobFinished(params, retry)
            }
            running.remove(params.jobId)
        }
        running[params.jobId] = job
        job.start()
        return true
    }

    private suspend fun synchronize(collectSensors: Boolean): Boolean = withContext(Dispatchers.IO) {
        val runtime = AndroidMobileWorkerRuntimeFactory.create(applicationContext)
        val sensors = AndroidMobileWorkerSensors(applicationContext)
        try {
            if (!runtime.status().enrolled) {
                false
            } else {
                if (collectSensors) {
                    try {
                        sensors.battery()?.let {
                            runtime.recordBattery(it.levelPercent, it.charging, it.lowPowerMode)
                        }
                        runtime.recordAirplaneMode(sensors.airplaneMode())
                        sensors.bluetoothEnabled()?.let { runtime.recordBluetoothPower(it) }
                        AndroidAutoSignals.capture(applicationContext, runtime)
                        sensors.captureConfiguredState(runtime)
                        AndroidSleepSignals(applicationContext).captureLatestSession(runtime)
                        sensors.synchronizeGeofences()
                        sensors.enableBlePresenceUpdates()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        androidMobileWorkerLog.warn { "Worker sensor collection failed: ${error::class.simpleName}" }
                    }
                }
                runtime.synchronize(WorkerAppState.BACKGROUND, heartbeatWhenIdle = true).pendingEventCount > 0
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            androidMobileWorkerLog.warn { "Scheduled Worker synchronization failed: ${error::class.simpleName}" }
            true
        } finally {
            runtime.close()
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        running.clear()
        super.onDestroy()
    }

    companion object {
        @Synchronized
        fun requestSynchronization(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val job = builder(context, IMMEDIATE_JOB_ID).build()
            scheduleJob { scheduler.enqueue(job, JobWorkItem(Intent())) }
        }

        @Synchronized
        private fun scheduleRetry(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(RETRY_JOB_ID) != null) return
            val job = builder(context, RETRY_JOB_ID)
                .setPersisted(true)
                .setMinimumLatency(30_000)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            scheduleJob { scheduler.schedule(job) }
        }

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(PERIODIC_JOB_ID) == null) {
                val job = builder(context, PERIODIC_JOB_ID).setPersisted(true).setPeriodic(15 * 60 * 1_000L).build()
                scheduleJob { scheduler.schedule(job) }
            }
            requestSynchronization(context)
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.let {
                it.cancel(PERIODIC_JOB_ID)
                it.cancel(IMMEDIATE_JOB_ID)
                it.cancel(RETRY_JOB_ID)
            }
        }

        private fun builder(context: Context, id: Int) =
            JobInfo.Builder(id, ComponentName(context, MobileWorkerSyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)

        private fun scheduleJob(submit: () -> Int) {
            val result = try {
                submit()
            } catch (error: IllegalStateException) {
                androidMobileWorkerLog.warn { "Worker scheduling quota exceeded; events remain in the outbox" }
                return
            }
            if (result != JobScheduler.RESULT_SUCCESS) {
                androidMobileWorkerLog.warn { "Worker synchronization could not be scheduled; events remain in the outbox" }
            }
        }

        private const val PERIODIC_JOB_ID = 27_042
        private const val IMMEDIATE_JOB_ID = 27_043
        private const val RETRY_JOB_ID = 27_044
    }
}
