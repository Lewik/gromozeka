package com.gromozeka.mobile.worker

import android.content.Context
import com.gromozeka.domain.model.UpdateComponent
import com.gromozeka.domain.model.UpdatePlatform
import com.gromozeka.updates.*
import com.gromozeka.worker.runtime.WorkerUpdateGate
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.*
import java.io.File

internal object AndroidWorkerApplicationUpdates {
    val gate = WorkerUpdateGate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(context: Context) {
        check(job == null)
        job = scope.launch {
            val runtime = AndroidMobileWorkerRuntimeFactory.create(context)
            val http = HttpClient(OkHttp) { followRedirects = false }
            var enrollment: MobileWorkerGatewayEnrollment? = null
            var agent: ApplicationUpdateAgent? = null
            try {
                while (isActive) {
                    try {
                        val current = runtime.updateEnrollment()
                        if (current != enrollment) {
                            agent?.close()
                            gate.resume()
                            enrollment = current
                            agent = current?.let {
                                ApplicationUpdateAgent(it.serverUrl, http, UpdateComponent.WORKER, UpdatePlatform.ANDROID,
                                    android.os.Build.MODEL, context.applicationVersion(),
                                    UpdateLocalStore(File(context.noBackupFilesDir, "updates/${UpdateLocalStore.key("${it.serverUrl}/${it.workerId}")}")),
                                    AndroidUpdateInstaller(context, prepareUpdate = { gate.prepare() }, resumeWork = { gate.resume() }),
                                    credential = it.credential)
                            }
                            AndroidUpdateActivity.agent = agent
                        }
                        agent?.checkNow()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        androidMobileWorkerLog.warn { "Application update check failed: ${error::class.simpleName}" }
                    }
                    delay(30_000)
                }
            } finally { agent?.close(); http.close(); runtime.close() }
        }
    }
}
