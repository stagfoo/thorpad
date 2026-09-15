package com.thorpad.app

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/**
 * The aiming itself: stick in, finger position out.
 *
 * NIKKE aims by reading a finger dragging across the screen. It has no idea a
 * gamepad exists and there is no button to map to, so the only way in is to be
 * that finger. Two things decide whether that feels like aiming or like
 * fighting the controls:
 *
 * Deflection is velocity, not position. A stick that snapped the touch to a
 * matching spot would turn every small correction into a jump.
 *
 * A drag has an end. The finger can only travel to the edge of its region
 * before it has to lift and start again, and that hitch is the thing you feel
 * mid-sweep. So the region is the whole screen by default, and a stroke starts
 * against the far side of it rather than in the middle — which is worth as much
 * again, because starting centred throws away half the travel before you have
 * begun.
 *
 * No Android types in here on purpose. Everything that decides how aiming feels
 * is arithmetic, and arithmetic can be checked against numbers on a desktop
 * rather than by squinting at a handheld.
 */
class AimEngine(private var config: AimSettings) {

    enum class Action { NONE, PRESS, MOVE, LIFT, RESTART }

    data class Step(val action: Action, val x: Float, val y: Float)

    private var x = 0.5f
    private var y = 0.5f
    private var down = false
    private var idleSince = -1L

    val isDown: Boolean get() = down

    fun reconfigure(next: AimSettings) {
        config = next
    }

    fun reset() {
        x = 0.5f
        y = 0.5f
        down = false
        idleSince = -1L
    }

    /**
     * One tick. [sx] and [sy] are the stick, already -1..1; [dt] is seconds
     * since the last tick; [nowMs] a monotonic clock.
     */
    fun step(sx: Float, sy: Float, dt: Float, nowMs: Long): Step {
        var dx = sx
        var dy = if (config.invertY) -sy else sy

        var mag = hypot(dx, dy)
        if (mag > 1f) {
            // A stick pushed into a corner reads 1.41 long. Left alone that
            // makes diagonal aiming 40% faster than straight, which feels like
            // the stick is broken.
            dx /= mag
            dy /= mag
            mag = 1f
        }

        if (mag <= config.deadzone) {
            if (!down) return Step(Action.NONE, x, y)
            if (idleSince < 0) idleSince = nowMs
            // Held for a moment rather than lifted on the instant: pausing
            // mid-sweep to line a shot up should not cost a whole stroke,
            // because getting one back costs a jump to the anchor.
            if (nowMs - idleSince >= config.holdMs) {
                down = false
                idleSince = -1L
                return Step(Action.LIFT, x, y)
            }
            return Step(Action.NONE, x, y)
        }
        idleSince = -1L

        // Rescaled past the deadzone, so the slowest movement is the first one
        // past its edge rather than a jump to whatever speed that edge sits at.
        val past = ((mag - config.deadzone) / (1f - config.deadzone)).coerceAtLeast(0f)
        val speed = config.maxSpeed * past.toDouble().pow(config.curve.toDouble()).toFloat()
        val stepX = (dx / mag) * speed * dt
        val stepY = (dy / mag) * speed * dt

        if (!down) {
            val anchor = anchorFor(config, dx, dy)
            x = anchor.first + stepX
            y = anchor.second + stepY
            down = true
            return Step(Action.PRESS, x, y)
        }

        val nx = x + stepX
        val ny = y + stepY

        if (nx < config.regionLeft || nx > config.regionRight ||
            ny < config.regionTop || ny > config.regionBottom
        ) {
            // Out of screen to drag across. Lift, go back to the far side,
            // press again — the hitch on a long sweep, and the whole reason the
            // region wants to be as large as the game will allow.
            val anchor = anchorFor(config, dx, dy)
            x = anchor.first + stepX
            y = anchor.second + stepY
            return Step(Action.RESTART, x, y)
        }

        x = nx
        y = ny
        return Step(Action.MOVE, x, y)
    }

    companion object {
        /**
         * Where a stroke begins, for a stick pointing [dx], [dy].
         *
         * Against the far side, opposite the way the stick points — not the
         * middle. Starting centred throws away half the region before the swing
         * has begun, so a sweep runs out of screen in half the distance it
         * could have had.
         */
        fun anchorFor(c: AimSettings, dx: Float, dy: Float): Pair<Float, Float> {
            val cx = (c.regionLeft + c.regionRight) / 2f
            val cy = (c.regionTop + c.regionBottom) / 2f
            val halfW = (c.regionRight - c.regionLeft) / 2f
            val halfH = (c.regionBottom - c.regionTop) / 2f

            val mag = hypot(dx, dy)
            if (mag < 1e-6f) return cx to cy

            // A margin, so a press never lands on the very edge, where a game is
            // apt to have an edge gesture or a UI button waiting.
            val margin = 0.04f
            return Pair(
                cx - (dx / mag) * halfW * c.anchorBias * (1f - margin),
                cy - (dy / mag) * halfH * c.anchorBias * (1f - margin),
            )
        }

        /** A raw axis reading as -1..1, given the range the device reports. */
        fun normalise(raw: Int, minimum: Int, maximum: Int): Float {
            if (maximum <= minimum) return 0f
            val mid = (minimum + maximum) / 2.0
            val half = (maximum - minimum) / 2.0
            return ((raw - mid) / half).coerceIn(-1.0, 1.0).toFloat()
        }

        /** Whether a reading is close enough to a range's centre to be rest. */
        fun isCentred(raw: Int, minimum: Int, maximum: Int, tolerance: Float = 0.02f): Boolean =
            abs(normalise(raw, minimum, maximum)) <= tolerance
    }
}
