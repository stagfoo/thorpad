package com.thorpad.app

import kotlin.math.hypot
import kotlin.math.pow

/**
 * A crosshair the stick pushes around.
 *
 * The alternative to dragging a finger, and a much smaller thing to ask of a
 * game: nothing is injected while the cursor moves, so there is no stroke to
 * run out of, no region edge to hit and no recentring hitch at all. A button
 * then taps wherever the crosshair happens to be.
 *
 * Whether a game can be aimed this way depends on the game — one that only
 * tracks a finger dragging will not care where a tap lands. That is worth
 * finding out on the device rather than arguing about, which is why both this
 * and the dragging kind exist.
 *
 * Pure: position in, position out, no Android types.
 */
class CursorEngine(private var config: AimSettings) {

    var x: Float = 0.5f
        private set
    var y: Float = 0.5f
        private set

    fun reconfigure(next: AimSettings) {
        config = next
    }

    /** Puts the crosshair in the middle of its region. */
    fun centre() {
        x = (config.regionLeft + config.regionRight) / 2f
        y = (config.regionTop + config.regionBottom) / 2f
    }

    /**
     * One tick. [sx] and [sy] are the stick, already -1..1; [dt] in seconds.
     *
     * Returns whether the cursor actually moved, so a caller can avoid
     * redrawing a screen that has not changed.
     */
    fun step(sx: Float, sy: Float, dt: Float): Boolean {
        var dx = sx
        var dy = if (config.invertY) -sy else sy

        var mag = hypot(dx, dy)
        if (mag > 1f) {
            // A stick in a corner reads 1.41 long; left alone that makes
            // diagonal movement 40% faster than straight.
            dx /= mag
            dy /= mag
            mag = 1f
        }
        if (mag <= config.deadzone) return false

        // Rescaled past the deadzone, so the slowest movement is the first one
        // past its edge rather than a jump to whatever speed that edge sits at.
        val past = ((mag - config.deadzone) / (1f - config.deadzone)).coerceAtLeast(0f)
        val speed = config.maxSpeed * past.toDouble().pow(config.curve.toDouble()).toFloat()

        val wasX = x
        val wasY = y
        // Clamped rather than wrapped or restarted: a crosshair that stops at
        // the edge is a crosshair you can push against and hold steady, which
        // is most of what aiming at the edge of a screen is.
        x = (x + (dx / mag) * speed * dt).coerceIn(config.regionLeft, config.regionRight)
        y = (y + (dy / mag) * speed * dt).coerceIn(config.regionTop, config.regionBottom)

        return x != wasX || y != wasY
    }
}
