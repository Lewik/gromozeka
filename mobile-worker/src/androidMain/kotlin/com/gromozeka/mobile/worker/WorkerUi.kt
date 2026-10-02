package com.gromozeka.mobile.worker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.gromozeka.shared.localization.BundledTranslations
import com.gromozeka.shared.localization.TranslationCatalog
import com.gromozeka.shared.localization.TranslationDirection
import java.util.Date
import kotlin.time.Instant

/** A thin Android adapter to the same catalogs/formatter used by the other clients. */
internal class WorkerStrings(locale: String) {
    private val catalog = TranslationCatalog(BundledTranslations.get(BundledTranslations.matchLocale(locale)))
    val rtl: Boolean get() = catalog.content.direction == TranslationDirection.RTL
    operator fun invoke(key: String, vararg args: Pair<String, Any?>): String = catalog.text("androidWorker.$key", *args)
}

internal fun Context.workerStrings(): WorkerStrings = WorkerStrings(resources.configuration.locales[0].toLanguageTag())

@Composable
internal fun rememberWorkerStrings(): WorkerStrings {
    val tag = LocalConfiguration.current.locales[0].toLanguageTag()
    return remember(tag) { WorkerStrings(tag) }
}

/** Retain semantic state, never localized text, across locale changes and service lifetimes. */
internal enum class WorkerPhase(val key: String) {
    STOPPED("off"), STARTING("starting"), ACTIVE("active"), WAITING_FIX("waitingFix"),
    WAITING_DELIVERY("waitingDelivery"), PAUSED("paused"), STORAGE_FULL("storageFull"),
    PERMISSION_REQUIRED("locationRequired"), NOTIFICATIONS_REQUIRED("notificationsRequired"), FAILED("failed"),
}

internal data class WorkerMessage(val key: String, val details: String? = null)
internal class WorkerUiException(val key: String) : IllegalArgumentException(key)
internal fun workerRequire(value: Boolean, key: String) { if (!value) throw WorkerUiException(key) }
internal fun Throwable.workerMessage(): WorkerMessage =
    if (this is WorkerUiException) WorkerMessage(key) else WorkerMessage("failed", "${javaClass.simpleName}: ${message.orEmpty()}")

internal fun Context.workerTime(value: Instant?): String = value?.let {
    val date = Date(it.toEpochMilliseconds())
    "${android.text.format.DateFormat.getDateFormat(this).format(date)} ${android.text.format.DateFormat.getTimeFormat(this).format(date)}"
} ?: "—"

/** OEM settings may not implement a package-specific intent. Fall back to the general page. */
internal fun Context.openWorkerSettings(action: String, packageSpecific: Boolean = false, extras: Intent.() -> Unit = {}) {
    val specific = Intent(action).apply {
        if (packageSpecific) data = Uri.fromParts("package", packageName, null)
        extras()
    }
    val choices = listOf(specific, Intent(action), Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    for (intent in choices) {
        try { startActivity(intent); return }
        catch (_: android.content.ActivityNotFoundException) { /* Try the OEM-independent page. */ }
        catch (_: SecurityException) { /* Restricted OEM page. */ }
    }
    throw WorkerUiException("settingsUnavailable")
}

@Composable
internal fun WorkerCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun WorkerDetails(
    label: String = rememberWorkerStrings()("settings"),
    leadingAction: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        leadingAction()
        TextButton(onClick = { expanded = !expanded }) { Text("${if (expanded) "−" else "+"} $label") }
    }
    if (expanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
internal fun WorkerMessageDialog(message: WorkerMessage?, onDismiss: () -> Unit) {
    if (message == null) return
    val t = rememberWorkerStrings()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t(message.key)) }, text = {
        message.details?.let { detail ->
            WorkerDetails(t("technicalDetails")) { Text(detail, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } })
}
