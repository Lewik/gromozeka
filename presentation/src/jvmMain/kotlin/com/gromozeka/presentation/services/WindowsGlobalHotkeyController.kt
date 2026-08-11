package com.gromozeka.presentation.services

import com.gromozeka.domain.model.QuickTextAction
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import klog.KLoggers

internal class WindowsGlobalHotkeyController : GlobalHotkeyController {
    private val log = KLoggers.logger(this)
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private val running = AtomicBoolean(false)
    private val hotkeyActions = mutableMapOf<Int, QuickTextAction.Id>()
    private var user32: User32? = null
    private var kernel32: Kernel32? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var messageThreadId = 0

    @Volatile
    private var messageThread: Thread? = null

    @Volatile
    private var actionHandler: ((QuickTextAction.Id) -> Unit)? = null

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
                val user32 = user32()
                val kernel32 = kernel32()
                this.user32 = user32
                this.kernel32 = kernel32
                messageThreadId = kernel32.GetCurrentThreadId()
                user32.PeekMessageW(MSG(), null, WM_USER, WM_USER, PM_NOREMOVE)
                initialized = true
                started.set(true)
                ready.countDown()
                log.info("Windows global hotkey service initialized")
                runMessageLoop(user32)
            }.onFailure { error ->
                log.warn(error) { "Windows global hotkey service unavailable: ${error.message}" }
                ready.countDown()
            }
            runCatching { unregisterHotkeys() }
            initialized = false
            running.set(false)
            messageThreadId = 0
        }

        if (!ready.await(2, TimeUnit.SECONDS) || !started.get()) {
            running.set(false)
        }
    }

    override fun registerQuickTextActionHotkeys(handler: (QuickTextAction.Id) -> Unit) {
        actionHandler = handler
        initializeService()
        if (!initialized) return

        postCommand {
            unregisterHotkeys()
            registerHotkey(
                id = FIX_TEXT_HOTKEY_ID,
                virtualKey = KEY_F,
                actionId = QuickTextAction.FIX_TEXT_ID,
            )
            registerHotkey(
                id = TRANSLATE_HOTKEY_ID,
                virtualKey = KEY_T,
                actionId = QuickTextAction.TRANSLATE_RU_EN_ID,
            )
        }
    }

    override fun cleanup() {
        if (!initialized && !running.get()) return

        postCommand { unregisterHotkeys() }
        postThreadMessage(WM_QUIT)
        messageThread?.join(1_000)
        messageThread = null
        actionHandler = null
    }

    override fun isSupported(): Boolean = true

    override fun getImplementationType(): String = "windows-user32"

    private fun runMessageLoop(user32: User32) {
        val message = MSG()
        while (running.get()) {
            when (val result = user32.GetMessageW(message, null, 0, 0)) {
                -1 -> {
                    log.warn("Windows hotkey message loop failed lastError=${Native.getLastError()}")
                    return
                }
                0 -> return
                else -> handleMessage(message)
            }
        }
    }

    private fun handleMessage(message: MSG) {
        when (message.message) {
            WM_HOTKEY -> {
                val actionId = hotkeyActions[message.wParam.toInt()] ?: return
                log.info("Received windows quick text hotkey id=${message.wParam.toInt()} actionId=${actionId.value}")
                actionHandler?.invoke(actionId)
            }
            WM_GROMOZEKA_COMMAND -> drainCommands()
        }
    }

    private fun registerHotkey(
        id: Int,
        virtualKey: Int,
        actionId: QuickTextAction.Id,
    ) {
        val user32 = user32 ?: return
        if (user32.RegisterHotKey(null, id, QUICK_TEXT_MODIFIERS, virtualKey)) {
            hotkeyActions[id] = actionId
            log.info("Registered windows quick text hotkey id=$id")
        } else {
            log.warn("Failed to register windows quick text hotkey id=$id lastError=${Native.getLastError()}")
        }
    }

    private fun unregisterHotkeys() {
        val user32 = user32
        hotkeyActions.keys.toList().forEach { id ->
            runCatching { user32?.UnregisterHotKey(null, id) }
                .onFailure { error -> log.warn(error) { "Failed to unregister windows hotkey: ${error.message}" } }
        }
        hotkeyActions.clear()
    }

    private fun postCommand(command: () -> Unit) {
        commands += command
        postThreadMessage(WM_GROMOZEKA_COMMAND)
    }

    private fun postThreadMessage(message: Int) {
        val threadId = messageThreadId
        if (threadId == 0) return
        user32?.PostThreadMessageW(threadId, message, NativeLong(0), NativeLong(0))
    }

    private fun drainCommands() {
        while (true) {
            val command = commands.poll() ?: return
            command()
        }
    }

    private fun user32(): User32 =
        Native.load("user32", User32::class.java)

    private fun kernel32(): Kernel32 =
        Native.load("kernel32", Kernel32::class.java)

    private interface User32 : Library {
        fun RegisterHotKey(
            hWnd: Pointer?,
            id: Int,
            fsModifiers: Int,
            vk: Int,
        ): Boolean

        fun UnregisterHotKey(
            hWnd: Pointer?,
            id: Int,
        ): Boolean

        fun GetMessageW(
            lpMsg: MSG,
            hWnd: Pointer?,
            wMsgFilterMin: Int,
            wMsgFilterMax: Int,
        ): Int

        fun PeekMessageW(
            lpMsg: MSG,
            hWnd: Pointer?,
            wMsgFilterMin: Int,
            wMsgFilterMax: Int,
            wRemoveMsg: Int,
        ): Boolean

        fun PostThreadMessageW(
            idThread: Int,
            msg: Int,
            wParam: NativeLong,
            lParam: NativeLong,
        ): Boolean
    }

    private interface Kernel32 : Library {
        fun GetCurrentThreadId(): Int
    }

    @Structure.FieldOrder("hwnd", "message", "wParam", "lParam", "time", "pt")
    open class MSG : Structure() {
        @JvmField
        var hwnd: Pointer? = null

        @JvmField
        var message: Int = 0

        @JvmField
        var wParam: NativeLong = NativeLong(0)

        @JvmField
        var lParam: NativeLong = NativeLong(0)

        @JvmField
        var time: Int = 0

        @JvmField
        var pt: POINT = POINT()
    }

    @Structure.FieldOrder("x", "y")
    open class POINT : Structure() {
        @JvmField
        var x: Int = 0

        @JvmField
        var y: Int = 0
    }

    private companion object {
        const val FIX_TEXT_HOTKEY_ID = 1
        const val TRANSLATE_HOTKEY_ID = 2
        const val WM_HOTKEY = 0x0312
        const val WM_QUIT = 0x0012
        const val WM_USER = 0x0400
        const val WM_GROMOZEKA_COMMAND = WM_USER + 61
        const val PM_NOREMOVE = 0
        const val KEY_F = 0x46
        const val KEY_T = 0x54
        const val MOD_ALT = 0x0001
        const val MOD_CONTROL = 0x0002
        const val MOD_WIN = 0x0008
        const val MOD_NOREPEAT = 0x4000
        const val QUICK_TEXT_MODIFIERS = MOD_ALT or MOD_CONTROL or MOD_WIN or MOD_NOREPEAT
    }
}
