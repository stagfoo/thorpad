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
        }
    }

    /**
     * Every key on the device passes through here.
     *
     * Only gamepad buttons bound to a control are consumed; everything else is
     * handed straight back, so back, volume and the keyboard behave exactly as
     * they did before this was installed.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean =
        OverlayService.instance?.onKey(event) ?: false

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    /** One quick touch at a point, in pixels. */
    fun tap(x: Float, y: Float, durationMs: Long = 60) {
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

    /** One finger held down, as a chain of continued strokes. */
    private inner class Hold(val id: String, val x: Float, val y: Float) {
        private var previous: GestureDescription.StrokeDescription? = null
        private var alive = true

        private val segmentMs = 120L

        fun begin() {
            step(first = true)
        }

        fun end() {
            alive = false
            val last = previous ?: return
            previous = null
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 0.1f, y)
            }
            dispatch(last.continueStroke(path, 0, 40, false), null)
        }

        private fun step(first: Boolean) {
            if (!alive) return
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 0.1f, y)
            }
            val stroke = if (first || previous == null) {
                GestureDescription.StrokeDescription(path, 0, segmentMs, true)
            } else {
                previous!!.continueStroke(path, 0, segmentMs, true)
            }
            previous = stroke
            dispatch(stroke) { if (alive) step(first = false) }
        }
    }
}
