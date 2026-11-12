package com.gromozeka.updates

import android.app.*
import android.content.*
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.AtomicFile
import com.gromozeka.domain.model.*
import java.io.File

class AndroidUpdateInstaller(
    context: Context,
    private val prepareUpdate: suspend () -> Boolean = { true },
    private val resumeWork: suspend () -> Unit = {},
) : ApplicationUpdateInstaller {
    private val context = context.applicationContext

    override suspend fun prepare(): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            notifyUpdate(context, context.getString(R.string.update_install_permission),
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return false
        }
        return prepareUpdate()
    }

    override suspend fun resume() = resumeWork()

    override suspend fun cancelPending(update: ApplicationUpdateRequest, store: UpdateLocalStore): Boolean {
        val session = File(store.directory, "session-${update.id}").takeIf(File::isFile)?.readText()?.toIntOrNull() ?: return false
        context.packageManager.packageInstaller.abandonSession(session)
        confirmationFile(store, update.id).delete()
        context.getSystemService(NotificationManager::class.java).cancel(UPDATE_NOTIFICATION_ID)
        return true
    }

    override suspend fun confirmPending(update: ApplicationUpdateRequest, store: UpdateLocalStore): Boolean {
        val session = File(store.directory, "session-${update.id}").takeIf(File::isFile)?.readText()?.toIntOrNull() ?: return false
        if (context.packageManager.packageInstaller.mySessions.none { it.sessionId == session }) return false
        val saved = confirmationFile(store, update.id)
        if (!saved.baseFile.isFile) return false
        val confirmation = Intent.parseUri(saved.readFully().decodeToString(), Intent.URI_INTENT_SCHEME)
        if (confirmation.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != session) return false
        context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    override suspend fun installationStopped(update: ApplicationUpdateRequest, store: UpdateLocalStore): Boolean {
        val handoff = File(store.directory, "handoff-${update.id}")
        val started = handoff.takeIf(File::isFile)?.readText()?.toLongOrNull() ?: return true
        if (System.currentTimeMillis() - started < 120_000) return false
        val session = File(store.directory, "session-${update.id}").takeIf(File::isFile)?.readText()?.toIntOrNull() ?: return true
        return context.packageManager.packageInstaller.mySessions.none { it.sessionId == session }
    }

    override suspend fun install(file: File, update: ApplicationUpdateRequest, store: UpdateLocalStore) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, flags)) { "Invalid APK" }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName || archive.versionName != update.artifact.version) throw UpdateInstallationRejected("APK identity does not match")
        @Suppress("DEPRECATION")
        if (!(if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode > installed.longVersionCode else archive.versionCode > installed.versionCode)) throw UpdateInstallationRejected("APK version code must increase")
        fun certificates(info: android.content.pm.PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo!!.apkContentsSigners else info.signatures!!)
                .map { UpdateLocalStore.key(it.toCharsString()) }.toSet()
        if (certificates(archive) != certificates(installed)) throw UpdateInstallationRejected("APK signing identity changed; this installation needs a one-time manual reinstall")
        val manager = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = manager.createSession(params)
        File(store.directory, "session-${update.id}").writeText(sessionId.toString())
        try {
            manager.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    file.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val callback = Intent(context, AndroidUpdateResultReceiver::class.java)
                    .setData(Uri.parse("gromozeka-update:${update.id}"))
                    .putExtra("requestId", update.id).putExtra("store", store.directory.name)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, sessionId, callback, flags).intentSender)
            }
        } catch (error: Exception) {
            manager.abandonSession(sessionId)
            throw error
        }
    }
}

class AndroidUpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("requestId") ?: return
        val key = intent.getStringExtra("store")?.takeIf { Regex("[a-f0-9]{64}").matches(it) } ?: return
        val store = UpdateLocalStore(File(context.noBackupFilesDir, "updates/$key"))
        if (store.read().report?.requestId != id) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                val session = File(store.directory, "session-$id").takeIf(File::isFile)?.readText()?.toIntOrNull() ?: return
                if (confirmation.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != session) return
                val saved = confirmationFile(store, id)
                val output = saved.startWrite()
                try {
                    output.write(confirmation.toUri(Intent.URI_INTENT_SCHEME).encodeToByteArray())
                    saved.finishWrite(output)
                } catch (error: Exception) {
                    saved.failWrite(output)
                    throw error
                }
                store.report(ApplicationUpdateReport(id, ApplicationUpdatePhase.WAITING_FOR_USER))
                notifyUpdate(context, context.getString(R.string.update_confirm), confirmation)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                confirmationFile(store, id).delete()
                store.report(ApplicationUpdateReport(id, ApplicationUpdatePhase.INSTALLING))
                context.getSystemService(NotificationManager::class.java).cancel(UPDATE_NOTIFICATION_ID)
            }
            else -> {
                store.report(ApplicationUpdateReport(id, ApplicationUpdatePhase.FAILED, "Android package installation failed"))
                confirmationFile(store, id).delete()
                notifyUpdate(context, context.getString(R.string.update_failed), Intent(context, AndroidUpdateActivity::class.java))
            }
        }
    }
}

private fun confirmationFile(store: UpdateLocalStore, id: String) =
    AtomicFile(File(store.directory, "confirmation-${UpdateLocalStore.key(id)}.uri"))

internal const val UPDATE_NOTIFICATION_ID = 4107

internal fun notifyUpdate(context: Context, text: String, intent: Intent) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("updates", context.getString(R.string.update_title), NotificationManager.IMPORTANCE_DEFAULT))
    val pending = PendingIntent.getActivity(context, UPDATE_NOTIFICATION_ID, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, "updates") else Notification.Builder(context)
    if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
        manager.notify(UPDATE_NOTIFICATION_ID, builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_title)).setContentText(text).setContentIntent(pending).setAutoCancel(true).build())
    }
}
