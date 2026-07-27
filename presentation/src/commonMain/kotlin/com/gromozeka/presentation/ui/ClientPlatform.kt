package com.gromozeka.presentation.ui

enum class ClientPlatform(
    val showSoftwareKeyboardControls: Boolean,
) {
    DESKTOP(showSoftwareKeyboardControls = false),
    ANDROID(showSoftwareKeyboardControls = true),
    IOS(showSoftwareKeyboardControls = true),
    WEB_DESKTOP(showSoftwareKeyboardControls = false),
    WEB_TOUCH(showSoftwareKeyboardControls = true),
}
