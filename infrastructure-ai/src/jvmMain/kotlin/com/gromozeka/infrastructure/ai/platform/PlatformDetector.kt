package com.gromozeka.infrastructure.ai.platform

object PlatformDetector {
    private val osName: String by lazy { System.getProperty("os.name").lowercase() }
    
    val isMacOS: Boolean get() = osName.contains("mac")
    val isWindows: Boolean get() = osName.contains("win")
    val isLinux: Boolean get() = osName.contains("nix") || osName.contains("nux") || osName.contains("aix")
    
    val currentPlatform: Platform get() = when {
        isMacOS -> Platform.MACOS
        isWindows -> Platform.WINDOWS
        isLinux -> Platform.LINUX
        else -> throw UnsupportedOperationException("Unsupported OS: $osName")
    }
    
    val osDisplayName: String get() = when (currentPlatform) {
        Platform.MACOS -> "macOS"
        Platform.WINDOWS -> "Windows" 
        Platform.LINUX -> "Linux"
    }
    
    fun isSupported(): Boolean = try {
        currentPlatform; true
    } catch (e: UnsupportedOperationException) {
        false
    }
}