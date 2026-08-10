package com.gromozeka.worker

import com.gromozeka.domain.service.ComputerUseAction
import com.gromozeka.domain.service.ComputerUseController
import com.gromozeka.domain.service.ComputerUseDisplay
import com.gromozeka.domain.service.ComputerUseDisplayId
import com.gromozeka.domain.service.ComputerUseMouseButton
import com.gromozeka.domain.service.ComputerUseObservation
import com.gromozeka.domain.service.ComputerUseObservationId
import com.gromozeka.domain.service.ComputerUseObservationReference
import com.gromozeka.domain.service.ComputerUsePoint
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.shared.uuid.uuid7
import com.sun.jna.Library
import com.sun.jna.Native
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import org.springframework.stereotype.Service
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

@Service
class JvmComputerUseController(
    private val identity: ConversationRuntimeWorkerIdentity,
    properties: ConversationRuntimeWorkerProperties,
    private val backend: ComputerUseBackend,
) : ComputerUseController {
    private val enabled = ConversationRuntimeCapability.COMPUTER_USE in properties.capabilities
    private val displayLocks = ConcurrentHashMap<ComputerUseDisplayId, Mutex>()

    override val available: Boolean
        get() = enabled && backend.available

    override val unavailableReason: String?
        get() = when {
            !enabled -> "Worker does not declare the COMPUTER_USE capability"
            else -> backend.unavailableReason
        }

    override suspend fun targets(): List<ComputerUseDisplay> {
        requireAvailable()
        return backend.targets()
    }

    override suspend fun observe(
        displayId: ComputerUseDisplayId,
        maxLongEdge: Int,
    ): ComputerUseObservation = displayLock(displayId).withLock {
        requireAvailable()
        backend.capture(identity, displayId, maxLongEdge)
    }

    override suspend fun act(
        observation: ComputerUseObservationReference,
        actions: List<ComputerUseAction>,
        maxLongEdge: Int,
        cancellationCheck: () -> Unit,
    ): ComputerUseObservation = displayLock(observation.displayId).withLock {
        requireAvailable()
        require(observation.workerId == identity.workerId && observation.workerSessionId == identity.sessionId) {
            "Computer Use observation belongs to another or restarted Worker; capture a fresh observation"
        }
        require(actions.isNotEmpty()) { "Computer Use action batch must contain at least one action" }
        require(actions.size <= MAX_ACTIONS_PER_BATCH) {
            "Computer Use action batch must contain at most $MAX_ACTIONS_PER_BATCH actions"
        }
        require(actions.sumOf(ComputerUseAction::estimatedDurationMillis) <= MAX_ACTION_BATCH_DURATION_MILLIS) {
            "Computer Use action batch estimated duration must not exceed $MAX_ACTION_BATCH_DURATION_MILLIS ms"
        }
        require(actions.sumOf(ComputerUseAction::typedCharacterCount) <= MAX_TYPED_CHARACTERS_PER_BATCH) {
            "Computer Use action batch must type at most $MAX_TYPED_CHARACTERS_PER_BATCH characters"
        }
        actions.forEach { it.requireInside(observation) }
        cancellationCheck()
        backend.execute(observation, actions, cancellationCheck)
        cancellationCheck()
        try {
            backend.capture(identity, observation.displayId, maxLongEdge)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw IllegalStateException(
                "Computer Use actions completed, but the follow-up screenshot failed; " +
                    "capture a fresh observation before continuing",
                error,
            )
        }
    }

    private fun requireAvailable() {
        check(available) { unavailableReason ?: "Computer Use is unavailable" }
    }

    private fun displayLock(displayId: ComputerUseDisplayId): Mutex =
        displayLocks.computeIfAbsent(displayId) { Mutex() }

    private companion object {
        const val MAX_ACTIONS_PER_BATCH = 32
        const val MAX_ACTION_BATCH_DURATION_MILLIS = 60_000L
        const val MAX_TYPED_CHARACTERS_PER_BATCH = 64_000
    }
}

interface ComputerUseBackend {
    val available: Boolean

    val unavailableReason: String?

    fun targets(): List<ComputerUseDisplay>

    fun capture(
        identity: ConversationRuntimeWorkerIdentity,
        displayId: ComputerUseDisplayId,
        maxLongEdge: Int,
    ): ComputerUseObservation

    fun execute(
        observation: ComputerUseObservationReference,
        actions: List<ComputerUseAction>,
        interruptionCheck: () -> Unit,
    )
}

class ComputerUseBackendExecutionException(
    val mutationStarted: Boolean,
    cause: Throwable,
) : RuntimeException(
    if (mutationStarted) {
        "Computer Use action sequence failed after desktop input may have started; outcome is unknown. " +
            "Capture a fresh observation before continuing: ${cause.message}"
    } else {
        "Computer Use action sequence failed before desktop input started: ${cause.message}"
    },
    cause,
)

@Service
class JvmComputerUseBackend(
    private val platformAccess: ComputerUsePlatformAccess,
) : ComputerUseBackend {
    override val available: Boolean
        get() = unavailableReason == null

    override val unavailableReason: String?
        get() = when {
            GraphicsEnvironment.isHeadless() -> "Worker has no graphical desktop"
            isUnsupportedWaylandSession() -> "Wayland desktop control is not supported; use an X11 session"
            else -> platformAccess.unavailableReason
        }

    override fun targets(): List<ComputerUseDisplay> {
        check(available) { unavailableReason ?: "Computer Use is unavailable" }
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val primary = environment.defaultScreenDevice.getIDstring()
        return environment.screenDevices.map { device ->
            val configuration = device.defaultConfiguration
            val bounds = configuration.bounds
            ComputerUseDisplay(
                id = ComputerUseDisplayId(device.getIDstring()),
                name = device.getIDstring(),
                originX = bounds.x,
                originY = bounds.y,
                logicalWidth = bounds.width,
                logicalHeight = bounds.height,
                scaleX = configuration.defaultTransform.scaleX,
                scaleY = configuration.defaultTransform.scaleY,
                primary = device.getIDstring() == primary,
            )
        }
    }

    override fun capture(
        identity: ConversationRuntimeWorkerIdentity,
        displayId: ComputerUseDisplayId,
        maxLongEdge: Int,
    ): ComputerUseObservation {
        require(maxLongEdge in 1024..4096) { "maxLongEdge must be between 1024 and 4096" }
        val device = requireDevice(displayId)
        val display = targets().first { it.id == displayId }
        val screenshot = Robot(device)
            .createMultiResolutionScreenCapture(device.defaultConfiguration.bounds)
            .resolutionVariants
            .maxBy { it.getWidth(null) * it.getHeight(null) }
            .toBufferedImage()
            .fitLongEdge(maxLongEdge)
            .encodeBoundedPng()
        return ComputerUseObservation(
            reference = ComputerUseObservationReference(
                id = ComputerUseObservationId(uuid7()),
                workerId = identity.workerId,
                workerSessionId = identity.sessionId,
                displayId = display.id,
                imageWidth = screenshot.width,
                imageHeight = screenshot.height,
                logicalOriginX = display.originX,
                logicalOriginY = display.originY,
                logicalWidth = display.logicalWidth,
                logicalHeight = display.logicalHeight,
                capturedAt = Clock.System.now(),
            ),
            png = screenshot.bytes,
        )
    }

    override fun execute(
        observation: ComputerUseObservationReference,
        actions: List<ComputerUseAction>,
        interruptionCheck: () -> Unit,
    ) {
        val robot = try {
            Robot(requireDevice(observation.displayId)).apply { autoDelay = 8 }
        } catch (error: Throwable) {
            throw ComputerUseBackendExecutionException(mutationStarted = false, cause = error)
        }
        val pressedKeys = linkedSetOf<Int>()
        val pressedButtons = linkedSetOf<Int>()
        var mutationStarted = false
        try {
            actions.forEach { action ->
                interruptionCheck()
                if (action !is ComputerUseAction.Wait) mutationStarted = true
                when (action) {
                    is ComputerUseAction.Move -> robot.movePointer(
                        observation.toDesktop(action.point),
                        action.durationMillis,
                        interruptionCheck,
                    )
                    is ComputerUseAction.Click -> {
                        action.point?.let { robot.movePointer(observation.toDesktop(it), 0, interruptionCheck) }
                        val mask = action.button.mask()
                        repeat(action.count) {
                            robot.mousePress(mask)
                            pressedButtons += mask
                            robot.mouseRelease(mask)
                            pressedButtons -= mask
                            if (action.count > 1) Thread.sleep(COMPUTER_USE_DOUBLE_CLICK_INTERVAL_MILLIS)
                        }
                    }
                    is ComputerUseAction.Drag -> {
                        val mask = action.button.mask()
                        robot.movePointer(observation.toDesktop(action.from), 0, interruptionCheck)
                        robot.mousePress(mask)
                        pressedButtons += mask
                        robot.movePointer(
                            observation.toDesktop(action.to),
                            action.durationMillis,
                            interruptionCheck,
                        )
                        robot.mouseRelease(mask)
                        pressedButtons -= mask
                    }
                    is ComputerUseAction.Scroll -> {
                        action.point?.let { robot.movePointer(observation.toDesktop(it), 0, interruptionCheck) }
                        robot.scroll(action.deltaX, action.deltaY, pressedKeys)
                    }
                    is ComputerUseAction.TypeText -> robot.typeText(
                        action.text,
                        action.intervalMillis,
                        pressedKeys,
                        interruptionCheck,
                    )
                    is ComputerUseAction.KeyChord -> robot.keyChord(action.keys, pressedKeys)
                    is ComputerUseAction.Wait -> interruptibleWait(action.durationMillis, interruptionCheck)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ComputerUseBackendExecutionException(mutationStarted, error)
        } finally {
            pressedButtons.toList().asReversed().forEach { runCatching { robot.mouseRelease(it) } }
            pressedKeys.toList().asReversed().forEach { runCatching { robot.keyRelease(it) } }
        }
    }

    private fun requireDevice(displayId: ComputerUseDisplayId): GraphicsDevice {
        check(available) { unavailableReason ?: "Computer Use is unavailable" }
        return GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .firstOrNull { it.getIDstring() == displayId.value }
            ?: error("Computer Use display not found: ${displayId.value}")
    }
}

interface ComputerUsePlatformAccess {
    val unavailableReason: String?
}

class JvmComputerUsePlatformAccess internal constructor(
    private val osName: String,
    private val screenCaptureAllowed: () -> Boolean,
    private val postEventAllowed: () -> Boolean,
) : ComputerUsePlatformAccess {
    constructor() : this(
        osName = System.getProperty("os.name"),
        screenCaptureAllowed = MacOsComputerUsePermissions::screenCaptureAllowed,
        postEventAllowed = MacOsComputerUsePermissions::postEventAllowed,
    )

    override val unavailableReason: String?
        get() {
            if (!osName.contains("mac", ignoreCase = true)) return null
            return permissionReason(
                permissionName = "Screen Recording",
                allowed = screenCaptureAllowed,
            ) ?: permissionReason(
                permissionName = "Accessibility",
                allowed = postEventAllowed,
            )
        }

    private fun permissionReason(
        permissionName: String,
        allowed: () -> Boolean,
    ): String? = try {
        if (allowed()) {
            null
        } else {
            "macOS $permissionName permission is missing for Gromozeka Worker; " +
                "grant it in System Settings > Privacy & Security and restart the Worker"
        }
    } catch (error: Throwable) {
        "macOS $permissionName permission could not be verified; Computer Use is disabled: " +
            (error.message ?: error::class.simpleName)
    }
}

private object MacOsComputerUsePermissions {
    fun screenCaptureAllowed(): Boolean =
        CoreGraphics.instance.CGPreflightScreenCaptureAccess().toInt() != 0

    fun postEventAllowed(): Boolean =
        CoreGraphics.instance.CGPreflightPostEventAccess().toInt() != 0

    private interface CoreGraphics : Library {
        fun CGPreflightScreenCaptureAccess(): Byte

        fun CGPreflightPostEventAccess(): Byte

        companion object {
            val instance: CoreGraphics by lazy {
                Native.load(
                    "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",
                    CoreGraphics::class.java,
                )
            }
        }
    }
}

private fun ComputerUseAction.requireInside(observation: ComputerUseObservationReference) {
    points().forEach { point ->
        require(point.x < observation.imageWidth && point.y < observation.imageHeight) {
            "Computer Use coordinate (${point.x}, ${point.y}) is outside observation " +
                "${observation.id.value} (${observation.imageWidth}x${observation.imageHeight})"
        }
    }
}

private fun ComputerUseAction.points(): List<ComputerUsePoint> = when (this) {
    is ComputerUseAction.Move -> listOf(point)
    is ComputerUseAction.Click -> listOfNotNull(point)
    is ComputerUseAction.Drag -> listOf(from, to)
    is ComputerUseAction.Scroll -> listOfNotNull(point)
    is ComputerUseAction.TypeText,
    is ComputerUseAction.KeyChord,
    is ComputerUseAction.Wait -> emptyList()
}

private fun ComputerUseObservationReference.toDesktop(point: ComputerUsePoint): java.awt.Point = java.awt.Point(
    logicalOriginX + (point.x.toDouble() * logicalWidth / imageWidth).roundToInt().coerceIn(0, logicalWidth - 1),
    logicalOriginY + (point.y.toDouble() * logicalHeight / imageHeight).roundToInt().coerceIn(0, logicalHeight - 1),
)

private fun Robot.movePointer(
    target: java.awt.Point,
    durationMillis: Long,
    interruptionCheck: () -> Unit,
) {
    if (durationMillis <= 0) {
        mouseMove(target.x, target.y)
        return
    }
    val start = java.awt.MouseInfo.getPointerInfo().location
    val steps = (durationMillis / 10).coerceIn(1, 500).toInt()
    repeat(steps) { index ->
        interruptionCheck()
        val progress = (index + 1).toDouble() / steps
        mouseMove(
            (start.x + (target.x - start.x) * progress).roundToInt(),
            (start.y + (target.y - start.y) * progress).roundToInt(),
        )
        Thread.sleep((durationMillis / steps).coerceAtLeast(1))
    }
}

private fun Robot.scroll(deltaX: Int, deltaY: Int, pressedKeys: MutableSet<Int>) {
    if (deltaY != 0) mouseWheel(deltaY.toWheelNotches())
    if (deltaX != 0) {
        keyPress(KeyEvent.VK_SHIFT)
        pressedKeys += KeyEvent.VK_SHIFT
        try {
            mouseWheel(deltaX.toWheelNotches())
        } finally {
            keyRelease(KeyEvent.VK_SHIFT)
            pressedKeys -= KeyEvent.VK_SHIFT
        }
    }
}

private fun Int.toWheelNotches(): Int =
    (ceil(abs(this) / 120.0).toInt().coerceAtLeast(1) * sign).coerceIn(-100, 100)

private val Int.sign: Int
    get() = if (this < 0) -1 else 1

private fun Robot.typeText(
    text: String,
    intervalMillis: Long,
    pressedKeys: MutableSet<Int>,
    interruptionCheck: () -> Unit,
) {
    val chunks = if (intervalMillis == 0L) listOf(text) else text.map(Char::toString)
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    val previous = runCatching { clipboard.getContents(null) }.getOrNull()
    try {
        chunks.forEach { chunk ->
            interruptionCheck()
            clipboard.setContents(StringSelection(chunk), null)
            val modifier = if (isMac()) KeyEvent.VK_META else KeyEvent.VK_CONTROL
            keyPress(modifier)
            pressedKeys += modifier
            keyPress(KeyEvent.VK_V)
            pressedKeys += KeyEvent.VK_V
            keyRelease(KeyEvent.VK_V)
            pressedKeys -= KeyEvent.VK_V
            keyRelease(modifier)
            pressedKeys -= modifier
            if (intervalMillis > 0) interruptibleWait(intervalMillis, interruptionCheck)
        }
    } finally {
        previous?.let { runCatching { clipboard.setContents(it, null) } }
    }
}

private fun Robot.keyChord(keys: List<String>, pressedKeys: MutableSet<Int>) {
    val keyCodes = keys.map(String::computerUseKeyCode)
    try {
        keyCodes.forEach {
            keyPress(it)
            pressedKeys += it
        }
    } finally {
        keyCodes.asReversed().forEach {
            keyRelease(it)
            pressedKeys -= it
        }
    }
}

internal fun String.computerUseKeyCode(): Int {
    val normalized = trim().uppercase(Locale.ROOT).replace("_", "").replace("-", "")
    return when (normalized) {
        "CTRL", "CONTROL" -> KeyEvent.VK_CONTROL
        "SHIFT" -> KeyEvent.VK_SHIFT
        "ALT", "OPTION" -> KeyEvent.VK_ALT
        "META", "CMD", "COMMAND" -> KeyEvent.VK_META
        "WINDOWS", "WIN" -> KeyEvent.VK_WINDOWS
        "SUPER" -> if (isWindows()) KeyEvent.VK_WINDOWS else KeyEvent.VK_META
        "ENTER", "RETURN" -> KeyEvent.VK_ENTER
        "TAB" -> KeyEvent.VK_TAB
        "ESC", "ESCAPE" -> KeyEvent.VK_ESCAPE
        "BACKSPACE" -> KeyEvent.VK_BACK_SPACE
        "DELETE", "DEL" -> KeyEvent.VK_DELETE
        "SPACE" -> KeyEvent.VK_SPACE
        "UP", "ARROWUP" -> KeyEvent.VK_UP
        "DOWN", "ARROWDOWN" -> KeyEvent.VK_DOWN
        "LEFT", "ARROWLEFT" -> KeyEvent.VK_LEFT
        "RIGHT", "ARROWRIGHT" -> KeyEvent.VK_RIGHT
        "HOME" -> KeyEvent.VK_HOME
        "END" -> KeyEvent.VK_END
        "PAGEUP" -> KeyEvent.VK_PAGE_UP
        "PAGEDOWN" -> KeyEvent.VK_PAGE_DOWN
        else -> when {
            normalized.matches(Regex("F([1-9]|1[0-9]|2[0-4])")) ->
                KeyEvent.VK_F1 + normalized.drop(1).toInt() - 1
            normalized.length == 1 -> KeyEvent.getExtendedKeyCodeForChar(normalized.first().code)
            else -> KeyEvent.VK_UNDEFINED
        }
    }.also { require(it != KeyEvent.VK_UNDEFINED) { "Unsupported Computer Use key: $this" } }
}

private fun ComputerUseMouseButton.mask(): Int = when (this) {
    ComputerUseMouseButton.LEFT -> InputEvent.BUTTON1_DOWN_MASK
    ComputerUseMouseButton.MIDDLE -> InputEvent.BUTTON2_DOWN_MASK
    ComputerUseMouseButton.RIGHT -> InputEvent.BUTTON3_DOWN_MASK
}

private fun ComputerUseAction.estimatedDurationMillis(): Long = when (this) {
    is ComputerUseAction.Move -> durationMillis
    is ComputerUseAction.Click -> count * COMPUTER_USE_DOUBLE_CLICK_INTERVAL_MILLIS
    is ComputerUseAction.Drag -> durationMillis
    is ComputerUseAction.Scroll -> 0
    is ComputerUseAction.TypeText -> text.length.toLong() * intervalMillis
    is ComputerUseAction.KeyChord -> 0
    is ComputerUseAction.Wait -> durationMillis
}

private fun ComputerUseAction.typedCharacterCount(): Int =
    (this as? ComputerUseAction.TypeText)?.text?.length ?: 0

private fun interruptibleWait(durationMillis: Long, interruptionCheck: () -> Unit) {
    var remaining = durationMillis
    while (remaining > 0) {
        interruptionCheck()
        val chunk = remaining.coerceAtMost(50)
        Thread.sleep(chunk)
        remaining -= chunk
    }
}

private fun java.awt.Image.toBufferedImage(): BufferedImage {
    if (this is BufferedImage) return this
    return BufferedImage(getWidth(null), getHeight(null), BufferedImage.TYPE_INT_ARGB).also { target ->
        val graphics = target.createGraphics()
        try {
            graphics.drawImage(this, 0, 0, null)
        } finally {
            graphics.dispose()
        }
    }
}

private fun BufferedImage.fitLongEdge(maxLongEdge: Int): BufferedImage {
    val longEdge = maxOf(width, height)
    if (longEdge <= maxLongEdge) return this
    return resize(maxLongEdge.toDouble() / longEdge)
}

internal fun BufferedImage.encodeBoundedPng(): EncodedComputerUsePng {
    var current = this
    while (true) {
        val encoded = current.encodePng()
        if (encoded.size <= MAX_SCREENSHOT_BYTES || maxOf(current.width, current.height) <= MIN_SCREENSHOT_LONG_EDGE) {
            return EncodedComputerUsePng(current.width, current.height, encoded)
        }
        current = current.resize(0.8)
    }
}

internal data class EncodedComputerUsePng(
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
)

private fun BufferedImage.resize(scale: Double): BufferedImage {
    val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
    val target = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
    val graphics = target.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.drawImage(this, 0, 0, targetWidth, targetHeight, null)
    } finally {
        graphics.dispose()
    }
    return target
}

private fun BufferedImage.encodePng(): ByteArray = ByteArrayOutputStream().use { output ->
    check(ImageIO.write(this, "png", output)) { "PNG encoder is unavailable" }
    output.toByteArray()
}

private fun isMac(): Boolean = System.getProperty("os.name").contains("mac", ignoreCase = true)

private fun isWindows(): Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true)

private fun isUnsupportedWaylandSession(): Boolean =
    System.getProperty("os.name").contains("linux", ignoreCase = true) &&
        (System.getenv("XDG_SESSION_TYPE")?.equals("wayland", ignoreCase = true) == true ||
            !System.getenv("WAYLAND_DISPLAY").isNullOrBlank())

private const val MAX_SCREENSHOT_BYTES = 3_500_000
private const val MIN_SCREENSHOT_LONG_EDGE = 768
private const val COMPUTER_USE_DOUBLE_CLICK_INTERVAL_MILLIS = 90L
