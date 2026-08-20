package com.gromozeka.presentation.services

import com.gromozeka.domain.model.KeyboardShortcutSettings
import kotlinx.coroutines.flow.StateFlow

internal class DesktopGlobalHotkeyController : GlobalHotkeyController {
    private val delegate: GlobalHotkeyController =
        when {
            System.getProperty("os.name").contains("mac", ignoreCase = true) -> MacOsGlobalHotkeyController()
            System.getProperty("os.name").contains("win", ignoreCase = true) -> WindowsGlobalHotkeyController()
            System.getProperty("os.name").contains("linux", ignoreCase = true) -> LinuxX11GlobalHotkeyController()
            else -> NoOpGlobalHotkeyController
        }

    override val state: StateFlow<GlobalHotkeyState>
        get() = delegate.state

    override fun initializeService() {
        delegate.initializeService()
    }

    override fun applySettings(
        settings: KeyboardShortcutSettings,
        handler: (GlobalHotkeyEvent) -> Unit,
    ) {
        delegate.applySettings(settings, handler)
    }

    override fun cleanup() {
        delegate.cleanup()
    }

    override fun isSupported(): Boolean =
        delegate.isSupported()

    override fun getImplementationType(): String =
        delegate.getImplementationType()
}
