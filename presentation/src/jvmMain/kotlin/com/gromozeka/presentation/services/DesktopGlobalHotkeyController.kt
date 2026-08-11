package com.gromozeka.presentation.services

import com.gromozeka.domain.model.QuickTextAction

internal class DesktopGlobalHotkeyController : GlobalHotkeyController {
    private val delegate: GlobalHotkeyController =
        when {
            System.getProperty("os.name").contains("mac", ignoreCase = true) -> MacOsGlobalHotkeyController()
            System.getProperty("os.name").contains("win", ignoreCase = true) -> WindowsGlobalHotkeyController()
            else -> NoOpGlobalHotkeyController
        }

    override fun initializeService() {
        delegate.initializeService()
    }

    override fun registerQuickTextActionHotkeys(handler: (QuickTextAction.Id) -> Unit) {
        delegate.registerQuickTextActionHotkeys(handler)
    }

    override fun cleanup() {
        delegate.cleanup()
    }

    override fun isSupported(): Boolean =
        delegate.isSupported()

    override fun getImplementationType(): String =
        delegate.getImplementationType()
}
