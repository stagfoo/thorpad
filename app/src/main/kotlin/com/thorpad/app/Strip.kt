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
