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
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser.HHOOK
import com.sun.jna.platform.win32.WinUser.KBDLLHOOKSTRUCT
import com.sun.jna.platform.win32.WinUser.LowLevelKeyboardProc
import com.sun.jna.platform.win32.WinUser.MSG
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import klog.KLoggers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class WindowsGlobalHotkeyController : GlobalHotkeyController {
    private val log = KLoggers.logger(this)
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private val running = AtomicBoolean(false)
    private val hotkeyActions = mutableMapOf<Int, KeyboardShortcutAction>()
    private val _state = MutableStateFlow(
        GlobalHotkeyState(available = true, implementationType = IMPLEMENTATION_TYPE)
    )
    private var user32: User32? = null
    private var keyboardHook: HHOOK? = null
    private var keyboardHookCallback: LowLevelKeyboardProc? = null
    private var holdBinding: KeyboardShortcutBinding? = null
    private var holdPressed = false

    @Volatile
    private var initialized = false

    @Volatile
    private var messageThreadId = 0

    @Volatile
    private var messageThread: Thread? = null

    @Volatile
    private var eventHandler: ((GlobalHotkeyEvent) -> Unit)? = null

    override val state: StateFlow<GlobalHotkeyState> = _state.asStateFlow()

    override fun initializeService() {
        if (initialized || running.get()) return

        val ready = CountDownLatch(1)
        val started = AtomicBoolean(false)
        running.set(true)
        messageThread = thread(
            start = true,
            isDaemon = true,
            name = "gromozeka-windows-hotkeys",
        ) {
            runCatching {
                val loadedUser32 = User32.INSTANCE
                user32 = loadedUser32
                messageThreadId = Kernel32.INSTANCE.GetCurrentThreadId()
                loadedUser32.PeekMessage(MSG(), null, WM_USER, WM_USER, PM_NOREMOVE)
                initialized = true
                started.set(true)
                ready.countDown()
                log.info("Windows global shortcut service initialized")
                runMessageLoop(loadedUser32)
            }.onFailure { error ->
                _state.value = GlobalHotkeyState(
                    available = false,
                    implementationType = IMPLEMENTATION_TYPE,
                    message = error.message ?: "Windows global shortcut initialization failed",
                )
                log.warn(error) { "Windows global shortcut service unavailable: ${error.message}" }
                ready.countDown()
            }
            runCatching { unregisterAll() }
            initialized = false
            running.set(false)
            messageThreadId = 0
        }

        if (!ready.await(2, TimeUnit.SECONDS) || !started.get()) running.set(false)
    }

    override fun applySettings(
        settings: KeyboardShortcutSettings,
        handler: (GlobalHotkeyEvent) -> Unit,
    ) {
        eventHandler = handler
        initializeService()
        if (!initialized) return

        val normalized = settings.normalized()
        val errors = KeyboardShortcutValidator.validate(normalized)
            .filter { it.severity == KeyboardShortcutValidationSeverity.ERROR }
            .associate { it.action to it.message }
        val bindings = normalized.bindings.filter {
            it.enabled && it.scope == KeyboardShortcutScope.GLOBAL && it.action !in errors
        }
        postCommand {
            unregisterAll()
            val registrationErrors = errors.toMutableMap()
            bindings.filter { it.action.activation == KeyboardShortcutActivation.ACTIVATE }
                .forEachIndexed { index, binding ->
                    registerHotkey(index + 1, binding)?.let { registrationErrors[binding.action] = it }
                }
            bindings.singleOrNull { it.action.activation == KeyboardShortcutActivation.HOLD }
                ?.let { binding ->
                    installHoldHook(binding)?.let { registrationErrors[binding.action] = it }
                }
            _state.value = GlobalHotkeyState(
                available = true,
                implementationType = IMPLEMENTATION_TYPE,
                bindingErrors = registrationErrors,
            )
        }
    }

    override fun cleanup() {
        if (!initialized && !running.get()) return
        postCommand { unregisterAll() }
        postThreadMessage(WM_QUIT)
        messageThread?.join(1_000)
        messageThread = null
        eventHandler = null
    }

    override fun isSupported(): Boolean = true

    override fun getImplementationType(): String = IMPLEMENTATION_TYPE

    private fun runMessageLoop(user32: User32) {
        val message = MSG()
        while (running.get()) {
            when (user32.GetMessage(message, null, 0, 0)) {
                -1 -> {
                    log.warn("Windows shortcut message loop failed lastError=${Native.getLastError()}")
                    return
                }
                0 -> return
                else -> handleMessage(message)
            }
        }
    }

    private fun handleMessage(message: MSG) {
        when (message.message) {
            WM_HOTKEY -> hotkeyActions[message.wParam.toInt()]?.let { action ->
                eventHandler?.invoke(GlobalHotkeyEvent(action, GlobalHotkeyEventPhase.TRIGGERED))
            }
            WM_GROMOZEKA_COMMAND -> drainCommands()
        }
    }

    private fun registerHotkey(id: Int, binding: KeyboardShortcutBinding): String? {
        val loadedUser32 = user32 ?: return "Windows User32 is unavailable"
        val virtualKey = binding.key.windowsVirtualKey()
        val modifiers = binding.modifiers.windowsModifiers() or MOD_NOREPEAT
        return if (loadedUser32.RegisterHotKey(null, id, modifiers, virtualKey)) {
            hotkeyActions[id] = binding.action
            log.info("Registered Windows global shortcut action=${binding.action}")
            null
        } else {
            "Shortcut registration failed (Windows error ${Native.getLastError()})"
        }
    }

    private fun installHoldHook(binding: KeyboardShortcutBinding): String? {
        val loadedUser32 = user32 ?: return "Windows User32 is unavailable"
        holdBinding = binding
        val callback = LowLevelKeyboardProc { code, eventType, event ->
            handleLowLevelKeyboardEvent(loadedUser32, code, eventType, event)
        }
        val hook = loadedUser32.SetWindowsHookEx(
            WH_KEYBOARD_LL,
            callback,
            Kernel32.INSTANCE.GetModuleHandle(null),
            0,
        )
        if (hook == null) {
            holdBinding = null
            return "Low-level keyboard hook failed (Windows error ${Native.getLastError()})"
        }
        keyboardHookCallback = callback
        keyboardHook = hook
        log.info("Registered Windows hold shortcut action=${binding.action}")
        return null
    }

    private fun handleLowLevelKeyboardEvent(
        user32: User32,
        code: Int,
        eventType: WPARAM,
        event: KBDLLHOOKSTRUCT,
    ): LRESULT {
        val binding = holdBinding
        if (code >= 0 && binding != null && event.vkCode == binding.key.windowsVirtualKey()) {
            val type = eventType.toInt()
            val matches = when (type) {
                WM_KEYDOWN, WM_SYSKEYDOWN -> binding.modifiers == currentModifiers(user32)
                WM_KEYUP, WM_SYSKEYUP -> holdPressed
                else -> false
            }
            if (matches) {
                when (type) {
                    WM_KEYDOWN, WM_SYSKEYDOWN -> if (!holdPressed) {
                        holdPressed = true
                        eventHandler?.invoke(GlobalHotkeyEvent(binding.action, GlobalHotkeyEventPhase.PRESSED))
                    }
                    WM_KEYUP, WM_SYSKEYUP -> if (holdPressed) {
                        holdPressed = false
                        eventHandler?.invoke(GlobalHotkeyEvent(binding.action, GlobalHotkeyEventPhase.RELEASED))
                    }
                }
                if (binding.consumeEvent) return LRESULT(1)
            }
        }
        return user32.CallNextHookEx(
            keyboardHook,
            code,
            eventType,
            LPARAM(Pointer.nativeValue(event.pointer)),
        )
    }

    private fun currentModifiers(user32: User32): Set<KeyboardShortcutModifier> = buildSet {
        if (user32.GetAsyncKeyState(VK_CONTROL).toInt() and KEY_DOWN_MASK != 0) {
            add(KeyboardShortcutModifier.CONTROL)
        }
        if (user32.GetAsyncKeyState(VK_MENU).toInt() and KEY_DOWN_MASK != 0) {
            add(KeyboardShortcutModifier.ALT)
        }
        if (user32.GetAsyncKeyState(VK_SHIFT).toInt() and KEY_DOWN_MASK != 0) {
            add(KeyboardShortcutModifier.SHIFT)
        }
        if (
            user32.GetAsyncKeyState(VK_LWIN).toInt() and KEY_DOWN_MASK != 0 ||
            user32.GetAsyncKeyState(VK_RWIN).toInt() and KEY_DOWN_MASK != 0
        ) {
            add(KeyboardShortcutModifier.META)
        }
    }

    private fun unregisterAll() {
        val loadedUser32 = user32
        if (holdPressed) {
            holdBinding?.let { binding ->
                eventHandler?.invoke(GlobalHotkeyEvent(binding.action, GlobalHotkeyEventPhase.CANCELLED))
            }
        }
        hotkeyActions.keys.toList().forEach { id ->
            runCatching { loadedUser32?.UnregisterHotKey(null, id) }
        }
        hotkeyActions.clear()
        keyboardHook?.let { hook -> runCatching { loadedUser32?.UnhookWindowsHookEx(hook) } }
        keyboardHook = null
        keyboardHookCallback = null
        holdBinding = null
        holdPressed = false
    }

    private fun postCommand(command: () -> Unit) {
        commands += command
        postThreadMessage(WM_GROMOZEKA_COMMAND)
    }

    private fun postThreadMessage(message: Int) {
        val threadId = messageThreadId
        if (threadId == 0) return
        user32?.PostThreadMessage(threadId, message, WPARAM(0), LPARAM(0))
    }

    private fun drainCommands() {
        while (true) {
            val command = commands.poll() ?: return
            command()
        }
    }

    private companion object {
        const val IMPLEMENTATION_TYPE = "windows-user32"
        const val WH_KEYBOARD_LL = 13
        const val WM_KEYDOWN = 0x0100
        const val WM_KEYUP = 0x0101
        const val WM_SYSKEYDOWN = 0x0104
        const val WM_SYSKEYUP = 0x0105
        const val WM_HOTKEY = 0x0312
        const val WM_QUIT = 0x0012
        const val WM_USER = 0x0400
        const val WM_GROMOZEKA_COMMAND = WM_USER + 61
        const val PM_NOREMOVE = 0
        const val MOD_NOREPEAT = 0x4000
        const val VK_SHIFT = 0x10
        const val VK_CONTROL = 0x11
        const val VK_MENU = 0x12
        const val VK_LWIN = 0x5B
        const val VK_RWIN = 0x5C
        const val KEY_DOWN_MASK = 0x8000
    }
}

private fun Set<KeyboardShortcutModifier>.windowsModifiers(): Int = fold(0) { result, modifier ->
    result or when (modifier) {
        KeyboardShortcutModifier.CONTROL -> 0x0002
        KeyboardShortcutModifier.ALT -> 0x0001
        KeyboardShortcutModifier.SHIFT -> 0x0004
        KeyboardShortcutModifier.META -> 0x0008
    }
}

internal fun KeyboardShortcutKey.windowsVirtualKey(): Int = when (this) {
    in KeyboardShortcutKey.A..KeyboardShortcutKey.Z -> 0x41 + ordinal - KeyboardShortcutKey.A.ordinal
    in KeyboardShortcutKey.DIGIT_0..KeyboardShortcutKey.DIGIT_9 ->
        0x30 + ordinal - KeyboardShortcutKey.DIGIT_0.ordinal
    in KeyboardShortcutKey.F1..KeyboardShortcutKey.F24 -> 0x70 + ordinal - KeyboardShortcutKey.F1.ordinal
    KeyboardShortcutKey.ESCAPE -> 0x1B
    KeyboardShortcutKey.SPACE -> 0x20
    KeyboardShortcutKey.ENTER -> 0x0D
    KeyboardShortcutKey.TAB -> 0x09
    KeyboardShortcutKey.BACKSPACE -> 0x08
    KeyboardShortcutKey.DELETE -> 0x2E
    KeyboardShortcutKey.ARROW_UP -> 0x26
    KeyboardShortcutKey.ARROW_DOWN -> 0x28
    KeyboardShortcutKey.ARROW_LEFT -> 0x25
    KeyboardShortcutKey.ARROW_RIGHT -> 0x27
    KeyboardShortcutKey.HOME -> 0x24
    KeyboardShortcutKey.END -> 0x23
    KeyboardShortcutKey.PAGE_UP -> 0x21
    KeyboardShortcutKey.PAGE_DOWN -> 0x22
    else -> error("Unsupported Windows shortcut key $this")
}
