package com.gromozeka.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.shared.localization.BundledTranslations
import com.gromozeka.shared.localization.TranslationMessage
import com.gromozeka.shared.localization.TranslationPackage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.impl.use
import org.jetbrains.skiko.loadBytesFromPath

@Composable
actual fun PlatformFontFallback(translation: Translation, content: @Composable () -> Unit) {
    val inheritedResolver = LocalFontFamilyResolver.current
    var resolver by remember { mutableStateOf(inheritedResolver) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(translation.content, attempt) {
        loading = true
        failure = null
        val pendingResolver = createFontFamilyResolver()
        try {
            val fonts = WebFallbackFonts.forPackage(translation.content)
            val downloads = fonts.map { it to WebFallbackFonts.download(it.file) }
            for ((font, download) in downloads) {
                try {
                    val bytes = download.await()
                    font.validate(bytes)
                    pendingResolver.preload(FontFamily(Font(font.id, bytes)))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    WebFallbackFonts.invalidate(font.file)
                    throw error
                }
            }
            resolver = pendingResolver
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            resolver = pendingResolver
            failure = error
        } finally {
            loading = false
        }
    }

    CompositionLocalProvider(LocalFontFamilyResolver provides resolver) {
        Box(propagateMinConstraints = true) {
            content()
            if (loading) {
                Box(
                    modifier = Modifier.matchParentSize()
                        .background(Color(0xFF111318))
                        .clickable(remember { MutableInteractionSource() }, indication = null) {}
                        .clearAndSetSemantics { contentDescription = translation.text("bootstrap.initializing") },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color(0xFF75ACFF))
                }
            }
        }
        failure?.let { error ->
            GromozekaTheme {
                AlertDialog(
                    onDismissRequest = { failure = null },
                    title = { Text(translation.text("bootstrap.failedToStart")) },
                    text = { Text(error.message ?: translation.text("common.unknownError")) },
                    confirmButton = {
                        TextButton(onClick = { attempt++ }) { Text(translation.text("browser.retry")) }
                    },
                    dismissButton = {
                        TextButton(onClick = { failure = null }) { Text(translation.text("common.close")) }
                    },
                )
            }
        }
    }
}

@Serializable
private data class WebFontCatalog(val fonts: List<WebFont>)

@Serializable
private data class WebFont(
    val id: String,
    val file: String,
    val ranges: List<List<Int>>,
    val locale: String? = null,
) {
    fun validate(bytes: ByteArray) {
        Data.makeFromBytes(bytes).use { data ->
            val typeface = FontMgr.default.makeFromData(data)
                ?: error("Skia could not decode font asset: fonts/$file")
            typeface.use {
                check(it.glyphsCount > 0) { "Font asset contains no glyphs: fonts/$file" }
            }
        }
    }

    fun contains(codepoint: Int): Boolean {
        var left = 0
        var right = ranges.lastIndex
        while (left <= right) {
            val middle = (left + right) ushr 1
            val range = ranges[middle]
            when {
                codepoint < range[0] -> right = middle - 1
                codepoint > range[1] -> left = middle + 1
                else -> return true
            }
        }
        return false
    }
}

private object WebFallbackFonts {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val downloads = mutableMapOf<String, Deferred<ByteArray>>()
    private val format = Json { ignoreUnknownKeys = true }
    private var catalog: WebFontCatalog? = null
    private val core = listOf(
        "NotoSans-Regular", "NotoSansHebrew-Regular", "NotoSansArabic-Regular",
        "NotoSansSymbols-Regular", "NotoSansSymbols2-Regular",
    )

    fun download(file: String): Deferred<ByteArray> = downloads.getOrPut(file) {
        scope.async {
            try {
                loadBytesFromPath("fonts/$file")
            } catch (error: Throwable) {
                downloads.remove(file)
                throw error
            }
        }
    }

    fun invalidate(file: String) {
        downloads.remove(file)
    }

    private suspend fun loadCatalog(): WebFontCatalog {
        catalog?.let { return it }
        return try {
            format.decodeFromString<WebFontCatalog>(download("catalog.json").await().decodeToString())
                .also { catalog = it }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            invalidate("catalog.json")
            throw error
        }
    }

    suspend fun forPackage(translation: TranslationPackage): List<WebFont> {
        val fonts = loadCatalog().fonts
        val byId = fonts.associateBy { it.id }
        val cjkLocale = BundledTranslations.matchLocale(translation.locale)
            .takeIf { it in setOf("ja", "ko", "zh-Hans", "zh-Hant") } ?: "zh-Hans"
        val selected = core.map(byId::getValue).toMutableList()
        selected += fonts.first { it.locale == cjkLocale }
        val uncovered = translation.codepoints().filterTo(mutableSetOf()) { point -> selected.none { it.contains(point) } }
        val candidates = fonts.filter { it !in selected }.toMutableList()
        while (uncovered.isNotEmpty()) {
            val font = candidates.maxByOrNull { candidate -> uncovered.count(candidate::contains) } ?: break
            if (uncovered.none(font::contains)) break
            selected += font
            candidates.remove(font)
            uncovered.removeAll(font::contains)
        }
        return selected
    }
}

private fun TranslationPackage.codepoints(): Set<Int> = buildSet {
    fun addText(text: String) {
        var index = 0
        while (index < text.length) {
            val first = text[index++].code
            if (first in 0xD800..0xDBFF && index < text.length && text[index].code in 0xDC00..0xDFFF) {
                add(0x10000 + ((first - 0xD800) shl 10) + (text[index++].code - 0xDC00))
            } else add(first)
        }
    }
    addText(name)
    messages.values.forEach { message ->
        when (message) {
            is TranslationMessage.Text -> addText(message.value)
            is TranslationMessage.Plural -> message.forms.values.forEach(::addText)
        }
    }
}
