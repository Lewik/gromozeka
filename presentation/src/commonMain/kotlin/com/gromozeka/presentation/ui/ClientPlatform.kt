package com.gromozeka.presentation.ui

enum class ClientPlatform(
    val showSoftwareKeyboardControls: Boolean,
    val usePlatformDensity: Boolean,
    val canPickProjectDirectory: Boolean,
) {
    DESKTOP(showSoftwareKeyboardControls = false, usePlatformDensity = false, canPickProjectDirectory = true),
    ANDROID(showSoftwareKeyboardControls = true, usePlatformDensity = true, canPickProjectDirectory = true),
    IOS(showSoftwareKeyboardControls = true, usePlatformDensity = true, canPickProjectDirectory = true),
    WEB_DESKTOP(showSoftwareKeyboardControls = false, usePlatformDensity = false, canPickProjectDirectory = false),
    WEB_TOUCH(showSoftwareKeyboardControls = true, usePlatformDensity = false, canPickProjectDirectory = false),
}
