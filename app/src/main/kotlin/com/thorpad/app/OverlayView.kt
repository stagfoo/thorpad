package com.thorpad.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * The controls, drawn over whatever is in front.
 *
 * Being visible is most of the point. Every previous attempt at this failed
 * silently somewhere in a chain nobody could see; here the overlay appearing
 * proves the window went up, a control lighting proves the button arrived, and
 * only then is there any question about whether the tap landed. Three stages,
 * each of which shows itself.
 */
class OverlayView(context: Context) : View(context) {

    var layout: Layout = Layout()
        set(value) {
            field = value
            invalidate()
        }

    /** In editing you place controls; otherwise touches pass straight through. */
    var editing: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** The control being configured, drawn picked out from the rest. */
    var selectedId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var onMoved: ((String, Float, Float) -> Unit)? = null
    var onPicked: ((String) -> Unit)? = null

    private val flashes = mutableMapOf<String, Long>()
    private val flashMs = 180L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 26f
        isFakeBoldText = true
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C8D4DE")
        textAlign = Paint.Align.CENTER
        textSize = 20f
    }
    private val banner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD54A")
        textAlign = Paint.Align.LEFT
        textSize = 24f
        isFakeBoldText = true
    }
    private val region = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }

    /** Tapping this flips out of editing, so the app need not be reopened. */
    var onDone: (() -> Unit)? = null
    private var doneRect: android.graphics.RectF? = null

    private var dragging: String? = null

    /** The crosshair, in fractions of the screen, once a cursor stick moves it. */
    private var cursorX: Float = -1f
    private var cursorY: Float = -1f

    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#FFD54A")
    }

    fun showCursor(x: Float, y: Float) {
        cursorX = x
        cursorY = y
        postInvalidateOnAnimation()
    }

    /**
     * Whether this window is holding focus to read the sticks.
     *
     * Only true below Android 14, where an accessibility service cannot see
     * analog axes at all and a focused window is the only thing that can. It is
     * a real cost — a focused overlay receives *every* key — so what it catches
     * and cannot use is forwarded on rather than swallowed.
     */
    var catchingSticks: Boolean = false

    var onMotion: ((MotionEvent) -> Unit)? = null
    var onKey: ((android.view.KeyEvent) -> Boolean)? = null

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        onMotion?.invoke(event)
        // Never consumed: anything else reading the pad still gets it.
        return false
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        onKey?.invoke(event) ?: false

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        onKey?.invoke(event) ?: false

    /** Lights a control up, so a press is visible before anything else is. */
    fun flash(id: String) {
        flashes[id] = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    private fun radiusFor(): Float = minOf(width, height) * 0.065f

    override fun onDraw(canvas: Canvas) {
        val radius = radiusFor()
        val now = SystemClock.uptimeMillis()
        var animating = false

        // A stick's region first and underneath, so it reads as the area the
        // finger will travel in rather than as another control.
        for (control in layout.controls) {
            if (!control.isStick) continue
            val box = control.region()
            region.color = if (control.bound) {
                Color.parseColor("#6FC9FF")
            } else {
                Color.parseColor("#E0725A")
            }
            region.alpha = if (editing) 170 else 50
            canvas.drawRect(
                box.left * width, box.top * height,
                box.right * width, box.bottom * height,
                region,
            )
        }

        for (control in layout.controls) {
            val cx = control.x * width
            val cy = control.y * height

            val since = flashes[control.id]?.let { now - it } ?: Long.MAX_VALUE
            val lit = since < flashMs
            if (lit) animating = true

            val base = when {
                lit -> Color.parseColor("#FFD54A")
                control.id == selectedId -> Color.parseColor("#6FC9FF")
                !control.bound -> Color.parseColor("#E0725A")
                else -> Color.parseColor("#7FD7A3")
            }

            // Faint while playing, solid while editing: in a game these sit on
            // top of the art and you want to see through them, but while
            // placing them you want to see them.
            val alpha = when {
                lit -> 220
                editing -> 200
                else -> 70
            }

            fill.color = base
            fill.alpha = alpha / 4
            canvas.drawCircle(cx, cy, radius, fill)

            ring.color = base
            ring.alpha = alpha
            canvas.drawCircle(cx, cy, radius, ring)

            text.alpha = alpha
            canvas.drawText(control.label, cx, cy + 9f, text)

            if (editing) {
                small.alpha = 220
                canvas.drawText(
                    when {
                        control.isStick && control.bound ->
                            "${control.stick!!.name.lowercase()} stick" +
                                if (control.isCursor) " · crosshair" else " · drag"
                        control.isStick -> "no stick"
                        control.bound -> Buttons.nameOf(control.keyCode) +
                            (if (control.press == Press.HOLD) " · hold" else "") +
                            (if (control.atCursor) " · at crosshair" else "")
                        control.id == selectedId -> "press a button now"
                        else -> "unbound"
                    },
                    cx,
                    cy + radius + 26f,
                    small,
                )
            }
        }

        if (cursorX >= 0f) {
            val cx = cursorX * width
            val cy = cursorY * height
            val arm = minOf(width, height) * 0.035f
            canvas.drawCircle(cx, cy, arm * 0.55f, cross)
            canvas.drawLine(cx - arm, cy, cx - arm * 0.3f, cy, cross)
            canvas.drawLine(cx + arm * 0.3f, cy, cx + arm, cy, cross)
            canvas.drawLine(cx, cy - arm, cx, cy - arm * 0.3f, cross)
            canvas.drawLine(cx, cy + arm * 0.3f, cx, cy + arm, cross)
        }

        if (editing) {
            // Said outright, because the reason taps do nothing while editing
            // is not guessable: the overlay has to accept touches so controls
            // can be dragged, which means it also catches the taps meant for
            // the game.
            canvas.drawText("EDITING — buttons won't reach the game", 24f, 48f, banner)
            small.alpha = 255
            small.textAlign = Paint.Align.LEFT
            canvas.drawText(
                if (selectedId != null) {
                    "press a gamepad button to bind it · drag to move · DONE"
                } else {
                    "tap a control to bind it · drag to move · DONE when finished"
                },
                24f, 76f, small,
            )
            small.textAlign = Paint.Align.CENTER

            val w = 150f
            val h = 64f
            val rect = android.graphics.RectF(
                width - w - 24f, 24f, width - 24f, 24f + h,
            )
            doneRect = rect
            fill.color = Color.parseColor("#7FD7A3")
            fill.alpha = 60
            canvas.drawRoundRect(rect, 14f, 14f, fill)
            ring.color = Color.parseColor("#7FD7A3")
            ring.alpha = 230
            canvas.drawRoundRect(rect, 14f, 14f, ring)
            text.alpha = 255
            canvas.drawText("DONE", rect.centerX(), rect.centerY() + 9f, text)
        } else {
            doneRect = null
        }

        flashes.entries.removeAll { now - it.value >= flashMs }
        if (animating) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editing) return false
        val radius = radiusFor()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                doneRect?.let { rect ->
                    if (rect.contains(event.x, event.y)) {
                        onDone?.invoke()
                        return true
                    }
                }
                val hit = layout.controls.firstOrNull {
                    val dx = it.x * width - event.x
                    val dy = it.y * height - event.y
                    dx * dx + dy * dy <= (radius * 1.4f) * (radius * 1.4f)
                } ?: return false
                dragging = hit.id
                selectedId = hit.id
                onPicked?.invoke(hit.id)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val id = dragging ?: return false
                onMoved?.invoke(id, event.x / width, event.y / height)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = dragging
                dragging = null
                return was != null
            }
        }
        return false
    }
}

/** Gamepad button names, for saying what a control is bound to. */
object Buttons {
    fun nameOf(keyCode: Int): String = when (keyCode) {
        0 -> "unbound"
        android.view.KeyEvent.KEYCODE_BUTTON_A -> "A"
        android.view.KeyEvent.KEYCODE_BUTTON_B -> "B"
        android.view.KeyEvent.KEYCODE_BUTTON_X -> "X"
        android.view.KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        android.view.KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
        android.view.KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
        android.view.KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
        android.view.KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
        android.view.KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
        android.view.KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
        android.view.KeyEvent.KEYCODE_BUTTON_START -> "Start"
        android.view.KeyEvent.KEYCODE_DPAD_UP -> "D-Up"
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> "D-Down"
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> "D-Left"
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> "D-Right"
        else -> android.view.KeyEvent.keyCodeToString(keyCode)
            .removePrefix("KEYCODE_")
    }
}
