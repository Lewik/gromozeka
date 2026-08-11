package com.gromozeka.presentation.services

import com.gromozeka.domain.model.QuickTextAction
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import klog.KLoggers

internal class MacOsGlobalHotkeyController : GlobalHotkeyController {
    private val log = KLoggers.logger(this)
    private var carbon: Carbon? = null
    private val registeredHotkeys = mutableListOf<Pointer>()
    private val hotkeyActions = mutableMapOf<Int, QuickTextAction.Id>()
    private var eventHandler: Carbon.EventHandlerProc? = null
    private var eventHandlerRef: Pointer? = null
    private var actionHandler: ((QuickTextAction.Id) -> Unit)? = null
    private var initialized = false

    override fun initializeService() {
        if (initialized) return

        runCatching {
            val carbon = carbon()
            val target = requireNotNull(carbon.GetEventDispatcherTarget()) {
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
            val status = carbon.InstallEventHandler(
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
            log.info("Mac global hotkey service initialized")
        }.onFailure { error ->
            log.warn(error) { "Mac global hotkey service unavailable: ${error.message}" }
        }
    }

    override fun registerQuickTextActionHotkeys(handler: (QuickTextAction.Id) -> Unit) {
        actionHandler = handler
        initializeService()
        if (!initialized) return

        unregisterHotkeys()
        registerHotkey(
            id = FIX_TEXT_HOTKEY_ID,
            keyCode = KEY_F,
            actionId = QuickTextAction.FIX_TEXT_ID,
        )
        registerHotkey(
            id = TRANSLATE_HOTKEY_ID,
            keyCode = KEY_T,
            actionId = QuickTextAction.TRANSLATE_RU_EN_ID,
        )
    }

    override fun cleanup() {
        unregisterHotkeys()
        val carbon = carbon
        eventHandlerRef?.let { ref ->
            runCatching { carbon?.RemoveEventHandler(ref) }
                .onFailure { error -> log.warn(error) { "Failed to remove mac hotkey handler: ${error.message}" } }
        }
        eventHandlerRef = null
        eventHandler = null
        initialized = false
    }

    override fun isSupported(): Boolean = true

    override fun getImplementationType(): String = "macos-carbon"

    private fun registerHotkey(
        id: Int,
        keyCode: Int,
        actionId: QuickTextAction.Id,
    ) {
        val carbon = carbon ?: return
        val outRef = PointerByReference()
        val hotkeyId = EventHotKeyID.ByValue().apply {
            signature = HOTKEY_SIGNATURE
            this.id = id
            write()
        }
        val status = carbon.RegisterEventHotKey(
            keyCode,
            QUICK_TEXT_MODIFIERS,
            hotkeyId,
            carbon.GetEventDispatcherTarget(),
            EVENT_HOTKEY_EXCLUSIVE,
            outRef,
        )
        if (status == 0) {
            val ref = outRef.value
            if (ref == null) {
                log.warn("Mac quick text hotkey id=$id registered without a native ref")
                return
            }
            registeredHotkeys += ref
            hotkeyActions[id] = actionId
            log.info("Registered mac quick text hotkey id=$id")
        } else {
            log.warn("Failed to register mac quick text hotkey id=$id status=$status")
        }
    }

    private fun unregisterHotkeys() {
        val carbon = carbon
        registeredHotkeys.forEach { ref ->
            runCatching { carbon?.UnregisterEventHotKey(ref) }
                .onFailure { error -> log.warn(error) { "Failed to unregister mac hotkey: ${error.message}" } }
        }
        registeredHotkeys.clear()
        hotkeyActions.clear()
    }

    private fun handleHotkeyEvent(event: Pointer?) {
        val carbon = carbon ?: return
        val hotkeyId = EventHotKeyID()
        val status = carbon.GetEventParameter(
            event,
            EVENT_PARAM_DIRECT_OBJECT,
            TYPE_EVENT_HOTKEY_ID,
            null,
            hotkeyId.size(),
            null,
            hotkeyId.pointer,
        )
        if (status != 0) {
            log.warn("Failed to read mac hotkey event parameter status=$status")
            return
        }
        hotkeyId.read()
        val actionId = hotkeyActions[hotkeyId.id] ?: return
        log.info("Received mac quick text hotkey id=${hotkeyId.id} actionId=${actionId.value}")
        actionHandler?.invoke(actionId)
    }

    private fun carbon(): Carbon =
        carbon ?: Native.load("Carbon", Carbon::class.java).also {
            carbon = it
        }

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
            fun callback(
                nextHandler: Pointer?,
                event: Pointer?,
                userData: Pointer?,
            ): Int
        }
    }

    @Structure.FieldOrder("eventClass", "eventKind")
    open class EventTypeSpec : Structure() {
        @JvmField
        var eventClass: Int = 0

        @JvmField
        var eventKind: Int = 0

        class ByReference : EventTypeSpec(), Structure.ByReference
    }

    @Structure.FieldOrder("signature", "id")
    open class EventHotKeyID : Structure() {
        @JvmField
        var signature: Int = 0

        @JvmField
        var id: Int = 0

        class ByValue : EventHotKeyID(), Structure.ByValue
    }

    private companion object {
        const val FIX_TEXT_HOTKEY_ID = 1
        const val TRANSLATE_HOTKEY_ID = 2
        const val EVENT_HOTKEY_PRESSED = 5
        const val EVENT_HOTKEY_EXCLUSIVE = 1
        const val KEY_F = 0x03
        const val KEY_T = 0x11
        const val QUICK_TEXT_MODIFIERS = (1 shl 8) or (1 shl 11) or (1 shl 12)
        val HOTKEY_SIGNATURE = fourCharCode("grmz")
        val EVENT_PARAM_DIRECT_OBJECT = fourCharCode("----")
        val TYPE_EVENT_HOTKEY_ID = fourCharCode("hkid")
    }
}

private fun fourCharCode(value: String): Int {
    require(value.length == 4) { "FourCharCode must contain exactly 4 chars" }
    return value.fold(0) { result, char -> (result shl 8) or char.code }
}
