package com.gromozeka.presentation.services

import com.gromozeka.presentation.services.theming.data.HexColor
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.Window
import klog.KLoggers

internal object WindowsWindowAppearance {
    private val log = KLoggers.logger(this)
    private val isWindows = System.getProperty("os.name").contains("win", ignoreCase = true)

    fun apply(
        window: Window,
        background: HexColor,
        foreground: HexColor,
    ) {
        if (!isWindows || !window.isDisplayable) return

        runCatching {
            val hwnd = HWND(Native.getComponentPointer(window))
            val api = Native.load("dwmapi", DwmApi::class.java)
            api.setAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, background.isDark().toNativeBoolean())
            api.setAttribute(hwnd, DWMWA_BORDER_COLOR, background.toWindowsColorRef())
            api.setAttribute(hwnd, DWMWA_CAPTION_COLOR, background.toWindowsColorRef())
            api.setAttribute(hwnd, DWMWA_TEXT_COLOR, foreground.toWindowsColorRef())
        }.onFailure { error ->
            log.warn(error) { "Failed to apply Windows window appearance: ${error.message}" }
        }
    }

    private fun DwmApi.setAttribute(hwnd: HWND, attribute: Int, value: Int) {
        val nativeValue = Memory(Int.SIZE_BYTES.toLong()).apply { setInt(0, value) }
        DwmSetWindowAttribute(hwnd, attribute, nativeValue, Int.SIZE_BYTES)
    }

    private fun HexColor.isDark(): Boolean {
        val rgb = hex.removePrefix("#").toInt(16)
        val red = rgb shr 16 and 0xFF
        val green = rgb shr 8 and 0xFF
        val blue = rgb and 0xFF
        return red * 299 + green * 587 + blue * 114 < 128_000
    }

    private fun HexColor.toWindowsColorRef(): Int {
        val rgb = hex.removePrefix("#").toInt(16)
        val red = rgb shr 16 and 0xFF
        val green = rgb shr 8 and 0xFF
        val blue = rgb and 0xFF
        return blue shl 16 or (green shl 8) or red
    }

    private fun Boolean.toNativeBoolean(): Int = if (this) 1 else 0

    private interface DwmApi : Library {
        fun DwmSetWindowAttribute(
            hwnd: HWND,
            attribute: Int,
            value: Memory,
            valueSize: Int,
        ): Int
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_BORDER_COLOR = 34
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36
}
