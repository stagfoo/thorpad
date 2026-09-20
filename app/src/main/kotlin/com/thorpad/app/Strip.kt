package com.thorpad.app

/**
 * A row or column of slots that a pair of buttons steps through.
 *
 * For a bar of things a game expects you to poke directly — a squad along the
 * bottom of the screen, a belt of items. There is no button in the game to bind
 * to, only five places to tap, so this makes them one axis: a shoulder button
 * to go forward, the other to come back, and every press taps the slot it
 * lands on.
 *
 * Pure: slot positions and stepping, no Android, so the geometry is checked
 * against numbers rather than by looking at where the taps went.
 */
object Strip {

    /** Sensible bounds. One slot is a button; past a dozen you cannot count them. */
    val SLOT_RANGE = 2..12

    /**
     * Where each slot sits, in fractions of the screen.
     *
     * Evenly spaced along the strip's own length and centred across its width,
     * so a five-slot strip laid over a five-slot bar lines up by construction
     * rather than by nudging each one into place.
     */
    fun slotPoints(control: Control): List<Pair<Float, Float>> {
        val count = control.slots.coerceIn(SLOT_RANGE)
        val along = if (control.vertical) control.height else control.width
        val centre = if (control.vertical) control.y else control.x
        val across = if (control.vertical) control.x else control.y

        val start = centre - along / 2f
        val step = along / count

        return (0 until count).map { i ->
            // The middle of each slot's share of the length, not its edge — a
            // strip divided at the boundaries would put the first tap on the
            // very end of the bar.
            val at = (start + step * (i + 0.5f)).coerceIn(0f, 1f)
            if (control.vertical) across to at else at to across
        }
    }

    /** The two ends of a strip, along its own axis, in fractions. */
    fun ends(control: Control): Pair<Float, Float> {
        val along = if (control.vertical) control.height else control.width
        val centre = if (control.vertical) control.y else control.x
        return (centre - along / 2f) to (centre + along / 2f)
    }

    /** How short a strip may get: below this the slots overlap into one blob. */
    const val MIN_LENGTH = 0.06f

    /**
     * Where a strip ends up when one end is dragged to [to].
     *
     * Returns the new centre and length along its axis. The end that was not
     * dragged stays exactly where it is — which is the whole point of dragging
     * an end rather than a slider: you are lining the strip up against
     * something you can see, one edge at a time.
     *
     * Dragging an end past the other flips rather than collapsing, because
     * stopping dead at zero length leaves a strip you cannot get back.
     */
    fun resizeFromEnd(control: Control, movingLowEnd: Boolean, to: Float): Pair<Float, Float> {
        val (low, high) = ends(control)
        val anchor = if (movingLowEnd) high else low
        val moved = to.coerceIn(0f, 1f)

        var length = kotlin.math.abs(moved - anchor)
        if (length < MIN_LENGTH) length = MIN_LENGTH

        val centre = if (moved < anchor) anchor - length / 2f else anchor + length / 2f
        // Pulled back inside the screen rather than clipped, so a strip dragged
        // off the edge keeps the length it was given.
        val half = length / 2f
        return centre.coerceIn(half, 1f - half) to length
    }

    /**
     * The slot a press lands on.
     *
     * Wraps, because a squad bar is a ring in practice: holding one shoulder
     * button should get you all the way round rather than stopping at the end
     * and making you use the other one.
     *
     * [from] of -1 means nothing is selected yet, so the first press forward
     * lands on the first slot and the first press back lands on the last.
     */
    fun step(from: Int, delta: Int, slots: Int): Int {
        val count = slots.coerceIn(SLOT_RANGE)
        if (from < 0) return if (delta >= 0) 0 else count - 1
        return ((from + delta) % count + count) % count
    }
}
