package com.gromozeka.updates

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch
import android.os.Build
import android.content.pm.PackageManager
import com.gromozeka.domain.model.ApplicationUpdatePhase
import kotlinx.coroutines.*

class AndroidUpdateActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4107)
        }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        val status = TextView(this)
        layout.addView(status)
        var actionError: String? = null
        fun button(text: Int, action: suspend () -> Unit): Button {
            return Button(this).apply {
                setText(text)
                setOnClickListener { scope.launch { try { actionError = null; action() } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { actionError = error.message ?: getString(R.string.update_failed) } } }
            }.also(layout::addView)
        }
        button(R.string.update_check) { agent?.checkNow() }
        val download = button(R.string.update_download) { agent?.requestUpdate(); agent?.checkNow() }
        val install = button(R.string.update_install) {
            if (agent?.view?.value?.installation?.request?.phase == ApplicationUpdatePhase.WAITING_FOR_USER) {
                agent?.confirmPendingInstallation()
            } else agent?.installReady()
        }
        val cancel = button(R.string.update_cancel_pending) { agent?.cancelPendingInstallation() }
        if (Build.VERSION.SDK_INT >= 26) button(R.string.update_allow) { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
        val automatic = Switch(this).apply { setText(R.string.update_automatic) }
        automatic.setOnCheckedChangeListener { _, checked -> scope.launch { runCatching { agent?.setAutomatic(checked) } } }
        layout.addView(automatic)
        setContentView(layout)
        scope.launch {
            while (isActive) {
                val view = agent?.view?.value
                status.text = view?.let { listOfNotNull(it.installation?.version, it.available?.version?.let { version -> getString(R.string.update_available, version) },
                    it.installation?.request?.phase?.let { phase -> getString(phase.textResource()) }, it.installation?.request?.message, actionError ?: it.error).joinToString("\n") }
                    ?: getString(R.string.update_connect_first)
                val phase = view?.installation?.request?.phase
                download.isEnabled = view?.available != null && phase?.terminal != false
                install.isEnabled = phase == ApplicationUpdatePhase.READY || phase == ApplicationUpdatePhase.WAITING_FOR_USER
                cancel.isEnabled = phase == ApplicationUpdatePhase.WAITING_FOR_USER
                automatic.isEnabled = view?.installation != null
                val enabled = view?.installation?.automatic == true
                if (automatic.isChecked != enabled) {
                    automatic.setOnCheckedChangeListener(null)
                    automatic.isChecked = enabled
                    automatic.setOnCheckedChangeListener { _, checked -> scope.launch { runCatching { agent?.setAutomatic(checked) } } }
                }
                delay(1000)
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        @Volatile var agent: ApplicationUpdateAgent? = null
    }
}

private fun ApplicationUpdatePhase.textResource() = when (this) {
    ApplicationUpdatePhase.QUEUED -> R.string.update_queued
    ApplicationUpdatePhase.WAITING_FOR_IDLE -> R.string.update_waiting
    ApplicationUpdatePhase.DOWNLOADING -> R.string.update_downloading
    ApplicationUpdatePhase.READY -> R.string.update_ready
    ApplicationUpdatePhase.WAITING_FOR_USER -> R.string.update_confirm
    ApplicationUpdatePhase.INSTALLING -> R.string.update_installing
    ApplicationUpdatePhase.SUCCEEDED -> R.string.update_succeeded
    ApplicationUpdatePhase.FAILED -> R.string.update_failed
    ApplicationUpdatePhase.CANCELLED -> R.string.update_cancelled
}
