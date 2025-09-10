package com.gromozeka.bot.platform

enum class Platform { MACOS, WINDOWS, LINUX }

interface GlobalHotkeyController {

    fun initializeService()

    fun cleanup()
    
    // Future extensions for native implementations:
    fun isSupported(): Boolean = false
    fun getSupportedPlatforms(): Set<Platform> = emptySet()
    fun getImplementationType(): String = "none"
}