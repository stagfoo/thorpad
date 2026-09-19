package com.thorpad.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * The crosshair, in a window of its own.
 *
 * It used to be painted on the same overlay as the controls, which meant
 * ducking took it with it — the overlay shrinks to a pixel so an injected touch
 * is not obscured, and the crosshair vanished for exactly as long as a button
 * was held. Which is exactly when it is being aimed by.
 *
 * So it lives in its own window now. That window never ducks, and it is only as
 * big as the crosshair rather than the whole screen, so what it obscures is a
 * thumbnail around the aim point instead of everything.
 */
class CrosshairView(context: Context) : View(context) {

    /** Radius in pixels. The window is sized from this. */
    var arm: Float = 40f
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FFD54A")
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // A dark outline under the yellow, because a crosshair over a bright
        // part of a game is otherwise invisible at the moment it matters.
        color = Color.parseColor("#66000000")
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val gap = arm * 0.3f

        paint.strokeWidth = (arm * 0.09f).coerceIn(2f, 6f)
        shadow.strokeWidth = paint.strokeWidth + 3f

        for (brush in listOf(shadow, paint)) {
            canvas.drawCircle(cx, cy, arm * 0.55f, brush)
            canvas.drawLine(cx - arm, cy, cx - gap, cy, brush)
            canvas.drawLine(cx + gap, cy, cx + arm, cy, brush)
            canvas.drawLine(cx, cy - arm, cx, cy - gap, brush)
            canvas.drawLine(cx, cy + gap, cx, cy + arm, brush)
        }
    }
}
