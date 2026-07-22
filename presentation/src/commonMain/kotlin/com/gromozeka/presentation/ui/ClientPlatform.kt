package com.gromozeka.presentation.ui

enum class ClientPlatform(
    val showSoftwareKeyboardControls: Boolean,
    val usePlatformDensity: Boolean,
) {
    DESKTOP(showSoftwareKeyboardControls = false, usePlatformDensity = false),
    ANDROID(showSoftwareKeyboardControls = true, usePlatformDensity = true),
    IOS(showSoftwareKeyboardControls = true, usePlatformDensity = true),
    WEB_DESKTOP(showSoftwareKeyboardControls = false, usePlatformDensity = false),
    WEB_TOUCH(showSoftwareKeyboardControls = true, usePlatformDensity = true),
}
