package com.thorpad.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Touching the screen on the app's behalf.
 *
 * `dispatchGesture` is the only route an ordinary app has into another app's
 * touch input without root. It is not an event you inject: you describe a
 * stroke with a duration and the system plays it out. A tap is one short
 * stroke. A hold is a chain — each segment declares `willContinue` and the next
 * starts from the previous one's end when it reports finished — because there
 * is no "press and stay pressed" in the API at all.
 */
class TapService : AccessibilityService() {

    companion object {
        @Volatile var instance: TapService? = null

        /** Whether the user has switched it on in Settings. */
        fun isEnabled(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val name = "${context.packageName}/${TapService::class.java.name}"
            val short = "${context.packageName}/.${TapService::class.java.simpleName}"
            return flat.split(':').any {
                it.equals(name, true) || it.equals(short, true)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val held = mutableMapOf<String, Hold>()

    /** Counters, so the UI can say whether anything is getting through. */
    @Volatile var sent: Int = 0
        private set
    @Volatile var completed: Int = 0
        private set
    @Volatile var refused: Int = 0
        private set
    @Volatile var lastError: String? = null
        private set

    var displayId: Int = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // A global key hook, which is the clean way to see a gamepad while a
        // game is in front. The alternative — an overlay window that holds
        // focus — works, but a focused overlay receives *every* key, including
        // back and volume, and there is nowhere to pass them on to because the
        // game no longer has focus. This sits above the focused window instead:
        // returning false from onKeyEvent lets the key through untouched.
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            if (Build.VERSION.SDK_INT >= 34) {
                flags = flags or AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS
            }
        }

        if (Build.VERSION.SDK_INT >= 34) {
            stickSource = try {
                serviceInfo = serviceInfo.apply {
                    setMotionEventSources(
                        android.view.InputDevice.SOURCE_JOYSTICK or
                            android.view.InputDevice.SOURCE_GAMEPAD
                    )
                }
                "accessibility motion events"
            } catch (e: Throwable) {
                "refused: ${e.message}"
            }
        } else {
            stickSource = "unavailable below Android 14"
        }
    }

    /** How sticks are being read, for saying so on screen. */
    @Volatile var stickSource: String = "not started"
        private set

    /**
     * Every key on the device passes through here.
     *
     * Only gamepad buttons bound to a control are consumed; everything else is
     * handed straight back, so back, volume and the keyboard behave exactly as
     * they did before this was installed.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean =
        OverlayService.instance?.onKey(event) ?: false

    /**
     * Gamepad sticks, on Android 14 and up.
     *
     * Analog axes are motion events, not key events, and before 14 an
     * accessibility service could not see them at all — the only route was a
     * window that held focus, which costs the back button. From 14 the system
     * will simply hand them over.
     *
     * Never consumed: anything else that reads the pad still gets it.
     */
    override fun onMotionEvent(event: android.view.MotionEvent) {
        OverlayService.instance?.onMotion(event)
    }

    /** Whether sticks can be read at all on this device. */
    val canReadSticks: Boolean get() = Build.VERSION.SDK_INT >= 34

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    /**
     * How long a tap presses for, in ms.
     *
     * Longer than feels necessary on purpose. A game reads touches once a
     * frame, so a tap shorter than two frames can have its press and release
     * fall inside the same one — the engine sees a finger appear and vanish
     * with no press between, which is why a touch can plainly register (a
     * ripple, a glow) while the button under it does nothing at all. At 30fps
     * a frame is 33ms, so 60 was never a safe number.
     */
    @Volatile var tapMs: Long = 140

    /**
     * How far a held finger wanders, in pixels.
     *
     * A hold that never moves emits a down and then nothing. Some engines take
     * that as a finger that is present but idle and stop acting on it, so a
     * button held to keep shooting quietly stops shooting. A pixel or two each
     * segment is a real movement event without being enough to drag anything.
     *
     * Adjustable because how much counts as "still moving" is the game's
     * decision, not something readable from out here.
     */
    @Volatile var holdJitter: Float = 2f

    /** One touch at a point, in pixels. */
    fun tap(x: Float, y: Float, durationMs: Long = tapMs) {
        val path = Path().apply {
            moveTo(x, y)
            // A stroke whose ends are identical is rejected as empty, so a tap
            // needs a tenth of a pixel of travel — far too little to drag
            // anything, enough to exist.
            lineTo(x + 0.1f, y)
        }
        dispatch(GestureDescription.StrokeDescription(path, 0, durationMs), null)
    }

    /**
     * Holds a point until [releaseHold] is called for the same [id].
     *
     * Keyed by control rather than by position, so two controls held at once
     * stay independent — and so a key-up releases the thing that key pressed,
     * not whatever happens to be down.
     */
    fun startHold(id: String, x: Float, y: Float) {
        if (held.containsKey(id)) return
        val hold = Hold(id, x, y)
        held[id] = hold
        hold.begin()
    }

    fun releaseHold(id: String) {
        held.remove(id)?.end()
    }

    fun releaseEverything() {
        for (hold in held.values.toList()) hold.end()
        held.clear()
    }

    val holding: Int get() = held.size

    private fun dispatch(
        stroke: GestureDescription.StrokeDescription,
        onDone: (() -> Unit)?,
    ): Boolean {
        val gesture = try {
            GestureDescription.Builder().apply {
                addStroke(stroke)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Public API, and the reason a two-screen handheld can be
                    // aimed at all: without it every gesture lands on whichever
                    // display the system considers default.
                    setDisplayId(displayId)
                }
            }.build()
        } catch (e: Throwable) {
            lastError = "build: ${e.message}"
            return false
        }

        sent++
        val ok = try {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) {
                        completed++
                        onDone?.invoke()
                    }

                    override fun onCancelled(description: GestureDescription?) {
                        // Usually the system taking the gesture away — another
                        // service, or the screen changing underneath.
                        refused++
                        onDone?.invoke()
                    }
                },
                main,
            )
        } catch (e: Throwable) {
            lastError = "dispatch: ${e.message}"
            false
        }

        if (!ok) {
            refused++
            lastError = lastError ?: "dispatchGesture refused the stroke"
        }
        return ok
    }

    /**
     * Starts a finger that can be dragged, and keeps dragging until released.
     *
     * Separate from [startHold] only in that the destination can change: a
     * stick needs the finger to keep moving, and a stroke that has already been
     * described cannot be redirected, so each segment aims at wherever the
     * target is when the previous one finishes. That completion is also the
     * pacing — asking where the stick is more often than a segment finishes
     * only queues work the system cannot deliver.
     */
    fun startDrag(id: String, x: Float, y: Float) {
        if (held.containsKey(id)) return
        val hold = Hold(id, x, y, segmentMs = 24L)
        held[id] = hold
        hold.begin()
    }

    /** Moves an already-started finger. Ignored if it is not down. */
    fun dragTo(id: String, x: Float, y: Float) {
        held[id]?.aimAt(x, y)
    }

    fun isDown(id: String): Boolean = held.containsKey(id)

    /** One finger down, as a chain of continued strokes. */
    private inner class Hold(
        val id: String,
        startX: Float,
        startY: Float,
        private val segmentMs: Long = 120L,
    ) {
        private var previous: GestureDescription.StrokeDescription? = null
        private var alive = true

        private var atX = startX
        private var atY = startY

        @Volatile private var wantX = startX
        @Volatile private var wantY = startY

        fun aimAt(x: Float, y: Float) {
            wantX = x
            wantY = y
        }

        fun begin() = step(first = true)

        fun end() {
            alive = false
            val last = previous ?: return
            previous = null
            dispatch(last.continueStroke(pathTo(atX, atY), 0, 40, false), null)
        }

        private var wander = 1f

        private fun pathTo(x: Float, y: Float): Path = Path().apply {
            moveTo(atX, atY)
            if (x == atX && y == atY) {
                // A stroke whose ends are identical is rejected as empty, and
                // one that barely moves reads to some engines as a finger that
                // has stopped. So a stationary hold drifts a pixel or two,
                // alternating, which keeps it within a fingertip of where it
                // was put while still being movement.
                wander = -wander
                val nudge = if (holdJitter <= 0f) 0.1f else holdJitter
                lineTo(x + nudge * wander, y)
            } else {
                lineTo(x, y)
            }
        }

        private fun step(first: Boolean) {
            if (!alive) return
            val toX = wantX
            val toY = wantY
            val path = pathTo(toX, toY)

            // A continuation of a stroke the system already cancelled is
            // refused, and taking that as the end of the hold means a button
            // held down quietly stops doing anything. Starting a fresh stroke
            // instead costs one lift and press that nobody sees.
            val stroke = try {
                if (first || previous == null) {
                    GestureDescription.StrokeDescription(path, 0, segmentMs, true)
                } else {
                    previous!!.continueStroke(path, 0, segmentMs, true)
                }
            } catch (e: Throwable) {
                previous = null
                GestureDescription.StrokeDescription(path, 0, segmentMs, true)
            }
            previous = stroke
            atX = toX
            atY = toY

            if (!dispatch(stroke) { if (alive) step(first = false) }) {
                // Refused outright rather than cancelled mid-flight: try again
                // from scratch on the next segment rather than going quiet.
                previous = null
                if (alive) main.postDelayed({ step(first = true) }, segmentMs)
            }
        }
    }
}
