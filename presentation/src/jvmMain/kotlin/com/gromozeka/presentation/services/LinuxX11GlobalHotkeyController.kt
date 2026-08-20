package com.gromozeka.presentation.services

import com.gromozeka.domain.model.KeyboardShortcutAction
import com.gromozeka.domain.model.KeyboardShortcutActivation
import com.gromozeka.domain.model.KeyboardShortcutBinding
import com.gromozeka.domain.model.KeyboardShortcutKey
import com.gromozeka.domain.model.KeyboardShortcutModifier
import com.gromozeka.domain.model.KeyboardShortcutScope
import com.gromozeka.domain.model.KeyboardShortcutSettings
import com.gromozeka.domain.model.KeyboardShortcutValidationSeverity
import com.gromozeka.domain.model.KeyboardShortcutValidator
import com.sun.jna.platform.unix.X11
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import klog.KLoggers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class LinuxX11GlobalHotkeyController : GlobalHotkeyController {
    private val log = KLoggers.logger(this)
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private val running = AtomicBoolean(false)
    private val bindingsByKeyCode = mutableMapOf<Int, MutableList<KeyboardShortcutBinding>>()
    private val pressedActions = mutableSetOf<KeyboardShortcutAction>()
    private val _state = MutableStateFlow(
        GlobalHotkeyState(available = true, implementationType = IMPLEMENTATION_TYPE)
    )
    private var display: X11.Display? = null
    private var rootWindow: X11.Window? = null
    private var eventHandler: ((GlobalHotkeyEvent) -> Unit)? = null
    private var eventThread: Thread? = null
    private var xErrorHandler: X11.XErrorHandler? = null
    private var previousXErrorHandler: X11.XErrorHandler? = null
    private var lastXError: Int? = null

    override val state: StateFlow<GlobalHotkeyState> = _state.asStateFlow()

    override fun initializeService() {
        if (running.get()) return
        if (System.getenv("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true)) {
            _state.value = GlobalHotkeyState(
                available = false,
                implementationType = IMPLEMENTATION_TYPE,
                message = "Global keyboard shortcuts are unavailable on Wayland",
            )
            return
        }

        val ready = CountDownLatch(1)
        var startupError: String? = null
        running.set(true)
        eventThread = thread(start = true, isDaemon = true, name = "gromozeka-linux-hotkeys") {
            val x11 = X11.INSTANCE
            val openedDisplay = x11.XOpenDisplay(null)
            if (openedDisplay == null) {
                startupError = "X11 display is unavailable"
                running.set(false)
                ready.countDown()
                return@thread
            }
            display = openedDisplay
            rootWindow = x11.XDefaultRootWindow(openedDisplay)
            val handler = X11.XErrorHandler { _, error ->
                error.read()
                lastXError = error.error_code.toInt() and 0xff
                0
            }
            xErrorHandler = handler
            previousXErrorHandler = x11.XSetErrorHandler(handler)
            ready.countDown()
            log.info("Linux X11 global shortcut service initialized")
            runEventLoop(x11, openedDisplay)
            unregisterAll(x11, openedDisplay)
            previousXErrorHandler?.let(x11::XSetErrorHandler)
            x11.XCloseDisplay(openedDisplay)
            display = null
            rootWindow = null
            running.set(false)
        }
        if (!ready.await(2, TimeUnit.SECONDS)) {
            running.set(false)
            startupError = "X11 shortcut service timed out during startup"
        }
        startupError?.let { message ->
            _state.value = GlobalHotkeyState(
                available = false,
                implementationType = IMPLEMENTATION_TYPE,
                message = message,
            )
        }
    }

    override fun applySettings(
        settings: KeyboardShortcutSettings,
        handler: (GlobalHotkeyEvent) -> Unit,
    ) {
        eventHandler = handler
        initializeService()
        if (!running.get()) return
        val normalized = settings.normalized()
        val errors = KeyboardShortcutValidator.validate(normalized)
            .filter { it.severity == KeyboardShortcutValidationSeverity.ERROR }
            .associate { it.action to it.message }
            .toMutableMap()
        val bindings = normalized.bindings.filter {
            it.enabled && it.scope == KeyboardShortcutScope.GLOBAL && it.action !in errors
        }
        bindings.filterNot(KeyboardShortcutBinding::consumeEvent).forEach { binding ->
            errors[binding.action] = "X11 global shortcuts are exclusive and must consume the key"
        }
        postCommand {
            val x11 = X11.INSTANCE
            val openedDisplay = display ?: return@postCommand
            unregisterAll(x11, openedDisplay)
            bindings.filter(KeyboardShortcutBinding::consumeEvent).forEach { binding ->
                registerBinding(x11, openedDisplay, binding)?.let { errors[binding.action] = it }
            }
            _state.value = GlobalHotkeyState(
                available = true,
                implementationType = IMPLEMENTATION_TYPE,
                bindingErrors = errors,
            )
        }
    }

    override fun cleanup() {
        running.set(false)
        eventThread?.join(1_000)
        eventThread = null
        eventHandler = null
        xErrorHandler = null
        previousXErrorHandler = null
    }

    override fun isSupported(): Boolean = _state.value.available

    override fun getImplementationType(): String = IMPLEMENTATION_TYPE

    private fun runEventLoop(x11: X11, display: X11.Display) {
        while (running.get()) {
            drainCommands()
            while (x11.XPending(display) > 0) {
                val event = X11.XEvent()
                x11.XNextEvent(display, event)
                event.read()
                if (event.type != X11.KeyPress && event.type != X11.KeyRelease) continue
                event.setType(X11.XKeyEvent::class.java)
                event.read()
                if (event.type == X11.KeyRelease && isAutoRepeatRelease(x11, display, event.xkey)) continue
                handleKeyEvent(event.xkey)
            }
            Thread.sleep(10)
        }
    }

    private fun isAutoRepeatRelease(
        x11: X11,
        display: X11.Display,
        release: X11.XKeyEvent,
    ): Boolean {
        if (x11.XPending(display) == 0) return false
        val next = X11.XEvent()
        x11.XPeekEvent(display, next)
        next.read()
        if (next.type != X11.KeyPress) return false
        next.setType(X11.XKeyEvent::class.java)
        next.read()
        return next.xkey.keycode == release.keycode && next.xkey.time == release.time
    }

    private fun handleKeyEvent(event: X11.XKeyEvent) {
        val keyCode = event.keycode and 0xff
        val bindings = bindingsByKeyCode[keyCode] ?: return
        when (event.type) {
            X11.KeyPress -> {
                val binding = bindings.firstOrNull { it.modifiers == event.state.toShortcutModifiers() } ?: return
                if (!pressedActions.add(binding.action)) return
                val phase = if (binding.action.activation == KeyboardShortcutActivation.HOLD) {
                    GlobalHotkeyEventPhase.PRESSED
                } else {
                    GlobalHotkeyEventPhase.TRIGGERED
                }
                eventHandler?.invoke(GlobalHotkeyEvent(binding.action, phase))
            }
            X11.KeyRelease -> bindings.firstOrNull { it.action in pressedActions }?.let { binding ->
                if (pressedActions.remove(binding.action) &&
                    binding.action.activation == KeyboardShortcutActivation.HOLD
                ) {
                    eventHandler?.invoke(GlobalHotkeyEvent(binding.action, GlobalHotkeyEventPhase.RELEASED))
                }
            }
        }
    }

    private fun registerBinding(
        x11: X11,
        display: X11.Display,
        binding: KeyboardShortcutBinding,
    ): String? {
        val root = rootWindow ?: return "X11 root window is unavailable"
        val keySym = x11.XStringToKeysym(binding.key.x11KeyName())
        val keyCode = x11.XKeysymToKeycode(display, keySym).toInt() and 0xff
        if (keyCode == 0) return "X11 cannot resolve key ${binding.key}"
        val baseModifiers = binding.modifiers.x11Modifiers()
        lastXError = null
        LOCK_VARIANTS.forEach { lockModifiers ->
            x11.XGrabKey(
                display,
                keyCode,
                baseModifiers or lockModifiers,
                root,
                0,
                X11.GrabModeAsync,
                X11.GrabModeAsync,
            )
        }
        x11.XSync(display, false)
        val error = lastXError
        if (error != null) {
            LOCK_VARIANTS.forEach { lockModifiers ->
                x11.XUngrabKey(display, keyCode, baseModifiers or lockModifiers, root)
            }
            return "X11 shortcut registration failed (error $error)"
        }
        bindingsByKeyCode.getOrPut(keyCode, ::mutableListOf) += binding
        log.info("Registered Linux X11 global shortcut action=${binding.action}")
        return null
    }

    private fun unregisterAll(x11: X11, display: X11.Display) {
        val root = rootWindow ?: return
        bindingsByKeyCode.values.flatten()
            .filter { it.action.activation == KeyboardShortcutActivation.HOLD && it.action in pressedActions }
            .forEach { binding ->
                eventHandler?.invoke(GlobalHotkeyEvent(binding.action, GlobalHotkeyEventPhase.CANCELLED))
            }
        bindingsByKeyCode.forEach { (keyCode, bindings) ->
            bindings.forEach { binding ->
                val baseModifiers = binding.modifiers.x11Modifiers()
                LOCK_VARIANTS.forEach { lockModifiers ->
                    x11.XUngrabKey(display, keyCode, baseModifiers or lockModifiers, root)
                }
            }
        }
        bindingsByKeyCode.clear()
        pressedActions.clear()
        x11.XSync(display, false)
    }

    private fun postCommand(command: () -> Unit) {
        commands += command
    }

    private fun drainCommands() {
        while (true) {
            val command = commands.poll() ?: return
            command()
        }
    }

    private companion object {
        const val IMPLEMENTATION_TYPE = "linux-x11"
        val LOCK_VARIANTS = intArrayOf(0, X11.LockMask, X11.Mod2Mask, X11.LockMask or X11.Mod2Mask)
    }
}

private fun Set<KeyboardShortcutModifier>.x11Modifiers(): Int = fold(0) { result, modifier ->
    result or when (modifier) {
        KeyboardShortcutModifier.CONTROL -> X11.ControlMask
        KeyboardShortcutModifier.ALT -> X11.Mod1Mask
        KeyboardShortcutModifier.SHIFT -> X11.ShiftMask
        KeyboardShortcutModifier.META -> X11.Mod4Mask
    }
}

private fun Int.toShortcutModifiers(): Set<KeyboardShortcutModifier> = buildSet {
    if (this@toShortcutModifiers and X11.ControlMask != 0) add(KeyboardShortcutModifier.CONTROL)
    if (this@toShortcutModifiers and X11.Mod1Mask != 0) add(KeyboardShortcutModifier.ALT)
    if (this@toShortcutModifiers and X11.ShiftMask != 0) add(KeyboardShortcutModifier.SHIFT)
    if (this@toShortcutModifiers and X11.Mod4Mask != 0) add(KeyboardShortcutModifier.META)
}

internal fun KeyboardShortcutKey.x11KeyName(): String = when (this) {
    in KeyboardShortcutKey.A..KeyboardShortcutKey.Z -> name.lowercase()
    in KeyboardShortcutKey.DIGIT_0..KeyboardShortcutKey.DIGIT_9 -> name.removePrefix("DIGIT_")
    in KeyboardShortcutKey.F1..KeyboardShortcutKey.F24 -> name
    KeyboardShortcutKey.ESCAPE -> "Escape"
    KeyboardShortcutKey.SPACE -> "space"
    KeyboardShortcutKey.ENTER -> "Return"
    KeyboardShortcutKey.TAB -> "Tab"
    KeyboardShortcutKey.BACKSPACE -> "BackSpace"
    KeyboardShortcutKey.DELETE -> "Delete"
    KeyboardShortcutKey.ARROW_UP -> "Up"
    KeyboardShortcutKey.ARROW_DOWN -> "Down"
    KeyboardShortcutKey.ARROW_LEFT -> "Left"
    KeyboardShortcutKey.ARROW_RIGHT -> "Right"
    KeyboardShortcutKey.HOME -> "Home"
    KeyboardShortcutKey.END -> "End"
    KeyboardShortcutKey.PAGE_UP -> "Prior"
    KeyboardShortcutKey.PAGE_DOWN -> "Next"
    else -> error("Unsupported X11 shortcut key $this")
}
