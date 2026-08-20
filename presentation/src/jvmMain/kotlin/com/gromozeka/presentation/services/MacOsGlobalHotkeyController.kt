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
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import klog.KLoggers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class MacOsGlobalHotkeyController : GlobalHotkeyController {
    private val log = KLoggers.logger(this)
    private val _state = MutableStateFlow(
        GlobalHotkeyState(available = true, implementationType = IMPLEMENTATION_TYPE)
    )
    private var carbon: Carbon? = null
    private val registeredHotkeys = mutableListOf<Pointer>()
    private val hotkeyActions = mutableMapOf<Int, KeyboardShortcutAction>()
    private var eventHandler: Carbon.EventHandlerProc? = null
    private var eventHandlerRef: Pointer? = null
    private var actionHandler: ((GlobalHotkeyEvent) -> Unit)? = null
    private var holdHook: MacOsHoldShortcutHook? = null
    private var initialized = false

    override val state: StateFlow<GlobalHotkeyState> = _state.asStateFlow()

    override fun initializeService() {
        if (initialized) return

        runCatching {
            val loadedCarbon = carbon()
            val target = requireNotNull(loadedCarbon.GetEventDispatcherTarget()) {
                "Carbon event dispatcher target is unavailable"
            }
            val eventSpec = EventTypeSpec.ByReference().apply {
                eventClass = fourCharCode("keyb")
                eventKind = EVENT_HOTKEY_PRESSED
                write()
            }
            val handlerRef = PointerByReference()
            val callback = Carbon.EventHandlerProc { _, event, _ ->
                handleHotkeyEvent(event)
                0
            }
            val status = loadedCarbon.InstallEventHandler(
                target,
                callback,
                1,
                eventSpec,
                null,
                handlerRef,
            )
            check(status == 0) { "InstallEventHandler failed with status $status" }
            eventHandler = callback
            eventHandlerRef = handlerRef.value
            initialized = true
            log.info("macOS global shortcut service initialized")
        }.onFailure { error ->
            _state.value = GlobalHotkeyState(
                available = false,
                implementationType = IMPLEMENTATION_TYPE,
                message = error.message ?: "macOS global shortcut initialization failed",
            )
            log.warn(error) { "macOS global shortcut service unavailable: ${error.message}" }
        }
    }

    override fun applySettings(
        settings: KeyboardShortcutSettings,
        handler: (GlobalHotkeyEvent) -> Unit,
    ) {
        actionHandler = handler
        initializeService()
        if (!initialized) return

        unregisterAll()
        val normalized = settings.normalized()
        val errors = KeyboardShortcutValidator.validate(normalized)
            .filter { it.severity == KeyboardShortcutValidationSeverity.ERROR }
            .associate { it.action to it.message }
            .toMutableMap()
        val bindings = normalized.bindings.filter {
            it.enabled && it.scope == KeyboardShortcutScope.GLOBAL && it.action !in errors
        }
        bindings.filter { it.action.activation == KeyboardShortcutActivation.ACTIVATE }
            .forEachIndexed { index, binding ->
                registerHotkey(index + 1, binding)?.let { errors[binding.action] = it }
            }
        bindings.singleOrNull { it.action.activation == KeyboardShortcutActivation.HOLD }
            ?.let { binding ->
                val hook = MacOsHoldShortcutHook(binding, handler)
                hook.start()?.let { errors[binding.action] = it } ?: run { holdHook = hook }
            }
        _state.value = GlobalHotkeyState(
            available = true,
            implementationType = IMPLEMENTATION_TYPE,
            bindingErrors = errors,
        )
    }

    override fun cleanup() {
        unregisterAll()
        val loadedCarbon = carbon
        eventHandlerRef?.let { ref -> runCatching { loadedCarbon?.RemoveEventHandler(ref) } }
        eventHandlerRef = null
        eventHandler = null
        initialized = false
    }

    override fun isSupported(): Boolean = true

    override fun getImplementationType(): String = IMPLEMENTATION_TYPE

    private fun registerHotkey(id: Int, binding: KeyboardShortcutBinding): String? {
        val loadedCarbon = carbon ?: return "Carbon is unavailable"
        val keyCode = runCatching(binding.key::macVirtualKey)
            .getOrElse { return it.message ?: "Unsupported macOS shortcut key ${binding.key}" }
        val outRef = PointerByReference()
        val hotkeyId = EventHotKeyID.ByValue().apply {
            signature = HOTKEY_SIGNATURE
            this.id = id
            write()
        }
        val status = loadedCarbon.RegisterEventHotKey(
            keyCode,
            binding.modifiers.macCarbonModifiers(),
            hotkeyId,
            loadedCarbon.GetEventDispatcherTarget(),
            if (binding.consumeEvent) EVENT_HOTKEY_EXCLUSIVE else 0,
            outRef,
        )
        if (status != 0) return "Shortcut registration failed (macOS status $status)"
        val ref = outRef.value ?: return "macOS registered the shortcut without a native reference"
        registeredHotkeys += ref
        hotkeyActions[id] = binding.action
        log.info("Registered macOS global shortcut action=${binding.action}")
        return null
    }

    private fun unregisterAll() {
        holdHook?.close()
        holdHook = null
        val loadedCarbon = carbon
        registeredHotkeys.forEach { ref -> runCatching { loadedCarbon?.UnregisterEventHotKey(ref) } }
        registeredHotkeys.clear()
        hotkeyActions.clear()
    }

    private fun handleHotkeyEvent(event: Pointer?) {
        val loadedCarbon = carbon ?: return
        val hotkeyId = EventHotKeyID()
        val status = loadedCarbon.GetEventParameter(
            event,
            EVENT_PARAM_DIRECT_OBJECT,
            TYPE_EVENT_HOTKEY_ID,
            null,
            hotkeyId.size(),
            null,
            hotkeyId.pointer,
        )
        if (status != 0) return
        hotkeyId.read()
        hotkeyActions[hotkeyId.id]?.let { action ->
            actionHandler?.invoke(GlobalHotkeyEvent(action, GlobalHotkeyEventPhase.TRIGGERED))
        }
    }

    private fun carbon(): Carbon = carbon ?: Native.load("Carbon", Carbon::class.java).also { carbon = it }

    private interface Carbon : Library {
        fun GetEventDispatcherTarget(): Pointer?
        fun InstallEventHandler(
            target: Pointer?,
            handler: EventHandlerProc,
            numTypes: Int,
            eventTypes: EventTypeSpec.ByReference,
            userData: Pointer?,
            outRef: PointerByReference?,
        ): Int
        fun RemoveEventHandler(handlerRef: Pointer?): Int
        fun RegisterEventHotKey(
            keyCode: Int,
            modifiers: Int,
            hotkeyId: EventHotKeyID.ByValue,
            target: Pointer?,
            options: Int,
            outRef: PointerByReference,
        ): Int
        fun UnregisterEventHotKey(hotkeyRef: Pointer?): Int
        fun GetEventParameter(
            event: Pointer?,
            name: Int,
            desiredType: Int,
            actualType: Pointer?,
            bufferSize: Int,
            actualSize: Pointer?,
            outData: Pointer?,
        ): Int

        fun interface EventHandlerProc : Callback {
            fun callback(nextHandler: Pointer?, event: Pointer?, userData: Pointer?): Int
        }
    }

    @Structure.FieldOrder("eventClass", "eventKind")
    open class EventTypeSpec : Structure() {
        @JvmField var eventClass: Int = 0
        @JvmField var eventKind: Int = 0
        class ByReference : EventTypeSpec(), Structure.ByReference
    }

    @Structure.FieldOrder("signature", "id")
    open class EventHotKeyID : Structure() {
        @JvmField var signature: Int = 0
        @JvmField var id: Int = 0
        class ByValue : EventHotKeyID(), Structure.ByValue
    }

    private companion object {
        const val IMPLEMENTATION_TYPE = "macos-carbon-core-graphics"
        const val EVENT_HOTKEY_PRESSED = 5
        const val EVENT_HOTKEY_EXCLUSIVE = 1
        val HOTKEY_SIGNATURE = fourCharCode("grmz")
        val EVENT_PARAM_DIRECT_OBJECT = fourCharCode("----")
        val TYPE_EVENT_HOTKEY_ID = fourCharCode("hkid")
    }
}

private class MacOsHoldShortcutHook(
    private val binding: KeyboardShortcutBinding,
    private val handler: (GlobalHotkeyEvent) -> Unit,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var callback: CoreGraphics.EventTapCallback? = null
    private var eventTap: Pointer? = null
    private var runLoop: Pointer? = null
    private var thread: Thread? = null
    private var pressed = false
    private var keyCode: Int? = null

    fun start(): String? {
        keyCode = runCatching(binding.key::macVirtualKey)
            .getOrElse { return it.message ?: "Unsupported macOS shortcut key ${binding.key}" }
        val coreGraphics = CoreGraphics.INSTANCE
        if (binding.consumeEvent && !coreGraphics.AXIsProcessTrusted()) {
            return "Global hold shortcuts that swallow keys require macOS Accessibility permission"
        }
        if (!binding.consumeEvent && !coreGraphics.CGPreflightListenEventAccess()) {
            return "Global hold shortcuts require macOS Input Monitoring permission"
        }

        val ready = CountDownLatch(1)
        var startupError: String? = null
        running.set(true)
        thread = thread(start = true, isDaemon = true, name = "gromozeka-macos-hold-shortcut") {
            val coreFoundation = CoreFoundation.INSTANCE
            val eventCallback = CoreGraphics.EventTapCallback { _, eventType, event, _ ->
                handleEvent(coreGraphics, eventType, event)
            }
            callback = eventCallback
            val tap = coreGraphics.CGEventTapCreate(
                CG_SESSION_EVENT_TAP,
                CG_HEAD_INSERT_EVENT_TAP,
                if (binding.consumeEvent) CG_EVENT_TAP_DEFAULT else CG_EVENT_TAP_LISTEN_ONLY,
                (1L shl CG_EVENT_KEY_DOWN) or (1L shl CG_EVENT_KEY_UP),
                eventCallback,
                null,
            )
            if (tap == null) {
                startupError = if (binding.consumeEvent) {
                    "macOS denied the global keyboard event tap; grant Accessibility permission"
                } else {
                    "macOS denied the global keyboard event tap; grant Input Monitoring permission"
                }
                running.set(false)
                ready.countDown()
                return@thread
            }
            eventTap = tap
            val source = coreFoundation.CFMachPortCreateRunLoopSource(null, tap, 0)
            val currentRunLoop = coreFoundation.CFRunLoopGetCurrent()
            runLoop = currentRunLoop
            coreFoundation.CFRunLoopAddSource(currentRunLoop, source, coreFoundationCommonModes())
            coreGraphics.CGEventTapEnable(tap, true)
            ready.countDown()
            coreFoundation.CFRunLoopRun()
            source?.let(coreFoundation::CFRelease)
            coreFoundation.CFRelease(tap)
            eventTap = null
            runLoop = null
            running.set(false)
        }
        if (!ready.await(2, TimeUnit.SECONDS)) {
            close()
            return "macOS keyboard event tap timed out during startup"
        }
        return startupError
    }

    private fun handleEvent(coreGraphics: CoreGraphics, eventType: Int, event: Pointer?): Pointer? {
        if (eventType == CG_EVENT_TAP_DISABLED_BY_TIMEOUT || eventType == CG_EVENT_TAP_DISABLED_BY_USER_INPUT) {
            eventTap?.let { coreGraphics.CGEventTapEnable(it, true) }
            return event
        }
        if (event == null || coreGraphics.CGEventGetIntegerValueField(event, CG_KEYBOARD_EVENT_KEYCODE).toInt() != keyCode) {
            return event
        }
        val matches = when (eventType) {
            CG_EVENT_KEY_DOWN -> binding.modifiers == coreGraphics.CGEventGetFlags(event).macModifiers()
            CG_EVENT_KEY_UP -> pressed
            else -> false
        }
        if (!matches) return event
        when (eventType) {
            CG_EVENT_KEY_DOWN -> if (!pressed) {
                pressed = true
                handler(GlobalHotkeyEvent(binding.action, GlobalHotkeyEventPhase.PRESSED))
            }
            CG_EVENT_KEY_UP -> if (pressed) {
                pressed = false
                handler(GlobalHotkeyEvent(binding.action, GlobalHotkeyEventPhase.RELEASED))
            }
        }
        return if (binding.consumeEvent) null else event
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        if (pressed) {
            handler(GlobalHotkeyEvent(binding.action, GlobalHotkeyEventPhase.CANCELLED))
        }
        runLoop?.let { CoreFoundation.INSTANCE.CFRunLoopStop(it) }
        thread?.join(1_000)
        thread = null
        callback = null
        pressed = false
        keyCode = null
    }

    private interface CoreGraphics : Library {
        fun CGEventTapCreate(
            tap: Int,
            place: Int,
            options: Int,
            eventsOfInterest: Long,
            callback: EventTapCallback,
            userInfo: Pointer?,
        ): Pointer?
        fun CGEventTapEnable(tap: Pointer?, enable: Boolean)
        fun CGEventGetIntegerValueField(event: Pointer?, field: Int): Long
        fun CGEventGetFlags(event: Pointer?): Long
        fun CGPreflightListenEventAccess(): Boolean
        fun AXIsProcessTrusted(): Boolean

        fun interface EventTapCallback : Callback {
            fun callback(proxy: Pointer?, type: Int, event: Pointer?, userInfo: Pointer?): Pointer?
        }

        companion object {
            val INSTANCE: CoreGraphics = Native.load("ApplicationServices", CoreGraphics::class.java)
        }
    }

    private interface CoreFoundation : Library {
        fun CFMachPortCreateRunLoopSource(allocator: Pointer?, port: Pointer?, order: Long): Pointer?
        fun CFRunLoopGetCurrent(): Pointer?
        fun CFRunLoopAddSource(runLoop: Pointer?, source: Pointer?, mode: Pointer?)
        fun CFRunLoopRun()
        fun CFRunLoopStop(runLoop: Pointer?)
        fun CFRelease(value: Pointer?)

        companion object {
            val INSTANCE: CoreFoundation = Native.load("CoreFoundation", CoreFoundation::class.java)
        }
    }

    private companion object {
        const val CG_SESSION_EVENT_TAP = 1
        const val CG_HEAD_INSERT_EVENT_TAP = 0
        const val CG_EVENT_TAP_DEFAULT = 0
        const val CG_EVENT_TAP_LISTEN_ONLY = 1
        const val CG_EVENT_KEY_DOWN = 10
        const val CG_EVENT_KEY_UP = 11
        const val CG_EVENT_TAP_DISABLED_BY_TIMEOUT = -2
        const val CG_EVENT_TAP_DISABLED_BY_USER_INPUT = -1
        const val CG_KEYBOARD_EVENT_KEYCODE = 9
    }
}

private fun coreFoundationCommonModes(): Pointer? = NativeLibrary.getInstance("CoreFoundation")
    .getGlobalVariableAddress("kCFRunLoopCommonModes")
    .getPointer(0)

private fun fourCharCode(value: String): Int {
    require(value.length == 4)
    return value.fold(0) { result, char -> (result shl 8) or char.code }
}

private fun Set<KeyboardShortcutModifier>.macCarbonModifiers(): Int = fold(0) { result, modifier ->
    result or when (modifier) {
        KeyboardShortcutModifier.META -> 1 shl 8
        KeyboardShortcutModifier.SHIFT -> 1 shl 9
        KeyboardShortcutModifier.ALT -> 1 shl 11
        KeyboardShortcutModifier.CONTROL -> 1 shl 12
    }
}

private fun Long.macModifiers(): Set<KeyboardShortcutModifier> = buildSet {
    if (this@macModifiers and (1L shl 17) != 0L) add(KeyboardShortcutModifier.SHIFT)
    if (this@macModifiers and (1L shl 18) != 0L) add(KeyboardShortcutModifier.CONTROL)
    if (this@macModifiers and (1L shl 19) != 0L) add(KeyboardShortcutModifier.ALT)
    if (this@macModifiers and (1L shl 20) != 0L) add(KeyboardShortcutModifier.META)
}

internal fun KeyboardShortcutKey.macVirtualKey(): Int = when (this) {
    KeyboardShortcutKey.A -> 0x00
    KeyboardShortcutKey.B -> 0x0B
    KeyboardShortcutKey.C -> 0x08
    KeyboardShortcutKey.D -> 0x02
    KeyboardShortcutKey.E -> 0x0E
    KeyboardShortcutKey.F -> 0x03
    KeyboardShortcutKey.G -> 0x05
    KeyboardShortcutKey.H -> 0x04
    KeyboardShortcutKey.I -> 0x22
    KeyboardShortcutKey.J -> 0x26
    KeyboardShortcutKey.K -> 0x28
    KeyboardShortcutKey.L -> 0x25
    KeyboardShortcutKey.M -> 0x2E
    KeyboardShortcutKey.N -> 0x2D
    KeyboardShortcutKey.O -> 0x1F
    KeyboardShortcutKey.P -> 0x23
    KeyboardShortcutKey.Q -> 0x0C
    KeyboardShortcutKey.R -> 0x0F
    KeyboardShortcutKey.S -> 0x01
    KeyboardShortcutKey.T -> 0x11
    KeyboardShortcutKey.U -> 0x20
    KeyboardShortcutKey.V -> 0x09
    KeyboardShortcutKey.W -> 0x0D
    KeyboardShortcutKey.X -> 0x07
    KeyboardShortcutKey.Y -> 0x10
    KeyboardShortcutKey.Z -> 0x06
    KeyboardShortcutKey.DIGIT_0 -> 0x1D
    KeyboardShortcutKey.DIGIT_1 -> 0x12
    KeyboardShortcutKey.DIGIT_2 -> 0x13
    KeyboardShortcutKey.DIGIT_3 -> 0x14
    KeyboardShortcutKey.DIGIT_4 -> 0x15
    KeyboardShortcutKey.DIGIT_5 -> 0x17
    KeyboardShortcutKey.DIGIT_6 -> 0x16
    KeyboardShortcutKey.DIGIT_7 -> 0x1A
    KeyboardShortcutKey.DIGIT_8 -> 0x1C
    KeyboardShortcutKey.DIGIT_9 -> 0x19
    KeyboardShortcutKey.F1 -> 0x7A
    KeyboardShortcutKey.F2 -> 0x78
    KeyboardShortcutKey.F3 -> 0x63
    KeyboardShortcutKey.F4 -> 0x76
    KeyboardShortcutKey.F5 -> 0x60
    KeyboardShortcutKey.F6 -> 0x61
    KeyboardShortcutKey.F7 -> 0x62
    KeyboardShortcutKey.F8 -> 0x64
    KeyboardShortcutKey.F9 -> 0x65
    KeyboardShortcutKey.F10 -> 0x6D
    KeyboardShortcutKey.F11 -> 0x67
    KeyboardShortcutKey.F12 -> 0x6F
    KeyboardShortcutKey.F13 -> 0x69
    KeyboardShortcutKey.F14 -> 0x6B
    KeyboardShortcutKey.F15 -> 0x71
    KeyboardShortcutKey.F16 -> 0x6A
    KeyboardShortcutKey.F17 -> 0x40
    KeyboardShortcutKey.F18 -> 0x4F
    KeyboardShortcutKey.F19 -> 0x50
    KeyboardShortcutKey.F20 -> 0x5A
    KeyboardShortcutKey.ESCAPE -> 0x35
    KeyboardShortcutKey.SPACE -> 0x31
    KeyboardShortcutKey.ENTER -> 0x24
    KeyboardShortcutKey.TAB -> 0x30
    KeyboardShortcutKey.BACKSPACE -> 0x33
    KeyboardShortcutKey.DELETE -> 0x75
    KeyboardShortcutKey.ARROW_UP -> 0x7E
    KeyboardShortcutKey.ARROW_DOWN -> 0x7D
    KeyboardShortcutKey.ARROW_LEFT -> 0x7B
    KeyboardShortcutKey.ARROW_RIGHT -> 0x7C
    KeyboardShortcutKey.HOME -> 0x73
    KeyboardShortcutKey.END -> 0x77
    KeyboardShortcutKey.PAGE_UP -> 0x74
    KeyboardShortcutKey.PAGE_DOWN -> 0x79
    KeyboardShortcutKey.F21,
    KeyboardShortcutKey.F22,
    KeyboardShortcutKey.F23,
    KeyboardShortcutKey.F24 -> error("macOS does not expose $this as a standard function key")
}
