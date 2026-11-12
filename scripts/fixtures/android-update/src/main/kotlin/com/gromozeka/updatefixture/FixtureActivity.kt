package com.gromozeka.updatefixture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.gromozeka.domain.model.*
import com.gromozeka.remote.protocol.*
import com.gromozeka.updates.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

class FixtureActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var agent: ApplicationUpdateAgent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this)
        setContentView(status)
        scope.launch {
            try {
                val store = UpdateLocalStore(File(noBackupFilesDir, "updates/${UpdateLocalStore.key("fixture")}"))
                val apk = File(filesDir, "next.apk")
                val artifact = ApplicationUpdateArtifact(UpdateComponent.CLIENT, UpdatePlatform.ANDROID, "2.0.0",
                    "next.apk", apk.length(), MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }, UpdatePackageFormat.APK)
                val update = ApplicationUpdateRequest("android-recovery", artifact, "2026-11-12T00:00:00Z")
                if (intent.getBooleanExtra("begin", false)) {
                    apk.copyTo(File(store.directory, artifact.fileName), overwrite = true)
                    store.report(ApplicationUpdateReport(update.id, ApplicationUpdatePhase.READY))
                }
                val version = packageManager.getPackageInfo(packageName, 0).versionName!!
                val http = HttpClient(MockEngine { request ->
                    check(request.url.encodedPath == "/api/updates/v1/client/poll")
                    val report = store.read().report!!
                    val phase = if (version == artifact.version) ApplicationUpdatePhase.SUCCEEDED else report.phase
                    val response = ApplicationUpdatePollResponse(ApplicationInstallation("fixture", User.Id("fixture"),
                        component = UpdateComponent.CLIENT, platform = UpdatePlatform.ANDROID, label = "Fixture", version = version,
                        lastSeenAt = update.requestedAt, request = update.copy(phase = phase)))
                    respond(Json.encodeToString(ApplicationUpdatePollResponse.serializer(), response), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                })
                agent = ApplicationUpdateAgent("http://127.0.0.1", http, UpdateComponent.CLIENT, UpdatePlatform.ANDROID,
                    "Fixture", version, store, AndroidUpdateInstaller(this@FixtureActivity), closeHttpClient = true)
                AndroidUpdateActivity.agent = agent
                agent!!.checkNow()
                if (intent.getBooleanExtra("begin", false)) {
                    check(agent!!.installReady())
                    status.text = "Submitted to PackageInstaller"
                } else {
                    startActivity(Intent(this@FixtureActivity, AndroidUpdateActivity::class.java))
                }
            } catch (error: Exception) {
                status.text = error.stackTraceToString()
                android.util.Log.e("UpdateFixture", "Fixture failed", error)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        agent?.close()
        super.onDestroy()
    }
}
