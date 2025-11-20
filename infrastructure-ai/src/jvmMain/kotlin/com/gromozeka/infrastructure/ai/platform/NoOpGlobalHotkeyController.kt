package com.gromozeka.infrastructure.ai.platform

import klog.KLoggers
import org.springframework.stereotype.Service

@Service
class NoOpGlobalHotkeyController : GlobalHotkeyController {
    private val log = KLoggers.logger(this)

    override fun initializeService() {
        log.info("Global hotkeys disabled - using NoOp implementation")
    }

    override fun cleanup() {
        log.debug("NoOp cleanup - nothing to do")
    }

    override fun isSupported(): Boolean = false
    
    override fun getSupportedPlatforms(): Set<Platform> = emptySet()
    
    override fun getImplementationType(): String = "disabled"
}