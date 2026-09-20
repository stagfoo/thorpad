package com.thorpad.app

import kotlin.math.hypot
import kotlin.math.pow

/**
 * Stick deflection as a finger held at an offset.
 *
 * This is how the mappers that work do it, and it is not what was built first.
 *
 * A game like NIKKE aims the way a virtual joystick does: you press, you hold
 * your finger away from where you pressed, and the view keeps turning for as
 * long as it stays there, at a rate set by how far away it is. So the stick
 * maps to a *position* — centre plus deflection times the radius — and a held
 * stick means a held offset, not a finger that keeps travelling.
 *
 * The first attempt treated deflection as velocity: the finger moved while the
 * stick was pushed. That is a mouse, not a thumb, and it brought the whole
 * problem of running out of screen with it — a drag ends at the region edge and
 * has to lift and start again. None of that exists here. The finger never
 * leaves the circle, so there is nothing to run out of, and the circle's size is
 * simply the top turn rate: a small one is slow and a big one is fast, which is
 * exactly the complaint about every other mapper's small fixed circle.
 *
 * Pure Dart of the Kotlin kind — no Android — so the mapping is checked against
 * numbers rather than by aiming at something.
 */
class DragStick(private var config: AimSettings) {

    enum class Action { NONE, PRESS, MOVE, LIFT }

    /** [x] and [y] are fractions of the screen. */
    data class Step(val action: Action, val x: Float, val y: Float)

    private var down = false
    private var idleSince = -1L
    private var atX = 0.5f
    private var atY = 0.5f

    val isDown: Boolean get() = down

    fun reconfigure(next: AimSettings) {
        config = next
    }

    fun reset() {
        down = false
        idleSince = -1L
    }

    /** The middle of the region, which is where a press lands. */
    fun centre(): Pair<Float, Float> = Pair(
        (config.regionLeft + config.regionRight) / 2f,
        (config.regionTop + config.regionBottom) / 2f,
    )

    /**
     * Where a deflection of [sx], [sy] puts the finger.
     *
     * Exposed so the mapping can be checked directly: full deflection should
     * reach the edge of the region and nothing should ever leave it.
     */
    fun offsetFor(sx: Float, sy: Float): Pair<Float, Float> {
        val (cx, cy) = centre()
        var dx = sx
        var dy = if (config.invertY) -sy else sy

        var mag = hypot(dx, dy)
        if (mag > 1f) {
            // A stick in a corner reads 1.41 long; left alone that makes
            // diagonal aiming 40% faster than straight.
            dx /= mag
            dy /= mag
            mag = 1f
        }
        if (mag <= config.deadzone) return cx to cy

        // Rescaled past the deadzone so the smallest useful push is the
        // slowest turn, then curved so a nudge is fine control and a shove is
        // a sweep — on one stick, which is the whole difficulty of aiming with
        // a thumb.
        val past = ((mag - config.deadzone) / (1f - config.deadzone)).coerceIn(0f, 1f)
        val pull = past.toDouble().pow(config.curve.toDouble()).toFloat()

        val halfW = (config.regionRight - config.regionLeft) / 2f
        val halfH = (config.regionBottom - config.regionTop) / 2f

        return Pair(
            (cx + (dx / mag) * pull * halfW).coerceIn(config.regionLeft, config.regionRight),
            (cy + (dy / mag) * pull * halfH).coerceIn(config.regionTop, config.regionBottom),
        )
    }

    /**
     * One tick. [nowMs] is a monotonic clock, for the hold after centring.
     */
    fun step(sx: Float, sy: Float, nowMs: Long): Step {
        var dx = sx
        var dy = if (config.invertY) -sy else sy
        var mag = hypot(dx, dy)
        if (mag > 1f) {
            dx /= mag
            dy /= mag
            mag = 1f
        }

        if (mag <= config.deadzone) {
            if (!down) return Step(Action.NONE, atX, atY)
            if (idleSince < 0) idleSince = nowMs
            // Held for a moment rather than lifted on the instant: letting go
            // of the stick between two small corrections should not cost a
            // press and a re-press, which the game sees as a fresh drag.
            if (nowMs - idleSince >= config.holdMs) {
                down = false
                idleSince = -1L
                return Step(Action.LIFT, atX, atY)
            }
            // While waiting out the hold the finger returns to centre, so the
            // view stops turning the moment the stick does.
            val (cx, cy) = centre()
            if (cx != atX || cy != atY) {
                atX = cx
                atY = cy
                return Step(Action.MOVE, atX, atY)
            }
            return Step(Action.NONE, atX, atY)
        }
        idleSince = -1L

        val (toX, toY) = offsetFor(sx, sy)

        if (!down) {
            // Pressed at the centre, not at the offset. The game reads the turn
            // from how far the finger is from where it went down, so going down
            // already displaced would throw the first frame of the sweep away.
            val (cx, cy) = centre()
            atX = cx
            atY = cy
            down = true
            return Step(Action.PRESS, cx, cy)
        }

        atX = toX
        atY = toY
        return Step(Action.MOVE, toX, toY)
    }
}
