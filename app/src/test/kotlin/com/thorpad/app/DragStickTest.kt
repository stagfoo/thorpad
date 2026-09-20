package com.thorpad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The mapping, as numbers.
 *
 * Everything here is a claim about how aiming with a thumb should behave: full
 * deflection reaches the edge, a held stick is a held offset, and nothing ever
 * leaves the circle. The device decides whether the *feel* is right; none of it
 * is needed to find out whether the arithmetic says what it means to.
 */
class DragStickTest {

    private fun full() = AimSettings()

    private fun boxed(size: Float) = AimSettings(
        regionLeft = 0.5f - size / 2,
        regionRight = 0.5f + size / 2,
        regionTop = 0.5f - size / 2,
        regionBottom = 0.5f + size / 2,
    )

    // ------------------------------------------------------- the offset model

    @Test fun `a held stick is a held offset, not a travelling finger`() {
        // The thing the first attempt got wrong. Deflection is a position, so
        // holding the stick holds the finger still — and the game keeps
        // turning because of where it is, not because it is moving.
        val stick = DragStick(full())
        stick.step(1f, 0f, 0)

        val first = stick.step(1f, 0f, 16)
        val later = stick.step(1f, 0f, 2000)

        assertEquals(first.x, later.x, 0.0001f)
        assertEquals(first.y, later.y, 0.0001f)
        assertTrue("and it never runs out of screen", later.x <= 1f)
    }

    @Test fun `full deflection reaches the edge of the region`() {
        val stick = DragStick(full())
        val (x, _) = stick.offsetFor(1f, 0f)
        assertEquals(1f, x, 0.001f)
    }

    @Test fun `a bigger region means a bigger offset, which is the turn rate`() {
        // The whole point of not being stuck with a small circle: the radius is
        // how fast the game can be made to turn.
        val small = DragStick(boxed(0.3f)).offsetFor(1f, 0f).first - 0.5f
        val big = DragStick(boxed(1f)).offsetFor(1f, 0f).first - 0.5f

        assertTrue("small $small vs big $big", big > small * 3f)
    }

    @Test fun `a centred stick sits at the centre`() {
        val (x, y) = DragStick(full()).offsetFor(0f, 0f)
        assertEquals(0.5f, x, 0.0001f)
        assertEquals(0.5f, y, 0.0001f)
    }

    @Test fun `nothing ever leaves the region`() {
        val stick = DragStick(boxed(0.4f))
        for (sx in listOf(-1f, -0.4f, 0f, 0.6f, 1f)) {
            for (sy in listOf(-1f, 0f, 1f)) {
                val (x, y) = stick.offsetFor(sx, sy)
                assertTrue("$sx,$sy -> $x", x in 0.3f..0.7f)
                assertTrue("$sx,$sy -> $y", y in 0.3f..0.7f)
            }
        }
    }

    @Test fun `a corner push is no further than a straight one`() {
        val stick = DragStick(full())
        val straight = stick.offsetFor(1f, 0f)
        val corner = stick.offsetFor(1f, 1f)

        val straightOut = hypot(straight.first - 0.5f, straight.second - 0.5f)
        val cornerOut = hypot(corner.first - 0.5f, corner.second - 0.5f)
        assertEquals(straightOut, cornerOut, 0.01f)
    }

    @Test fun `a nudge past the deadzone is a far smaller offset than full tilt`() {
        val c = full()
        val stick = DragStick(c)
        val nudge = stick.offsetFor(c.deadzone + 0.02f, 0f).first - 0.5f
        val shove = stick.offsetFor(1f, 0f).first - 0.5f

        assertTrue("nudge $nudge, shove $shove", shove > nudge * 20f)
    }

    // ------------------------------------------------------------ the finger

    @Test fun `the press lands at the centre, not at the offset`() {
        // The game reads the turn from how far the finger is from where it went
        // down, so pressing already displaced throws the first frame away.
        val step = DragStick(full()).step(1f, 0f, 0)
        assertEquals(DragStick.Action.PRESS, step.action)
        assertEquals(0.5f, step.x, 0.0001f)
    }

    @Test fun `the offset arrives on the tick after the press`() {
        val stick = DragStick(full())
        stick.step(1f, 0f, 0)
        val step = stick.step(1f, 0f, 16)

        assertEquals(DragStick.Action.MOVE, step.action)
        assertEquals(1f, step.x, 0.001f)
    }

    @Test fun `a resting stick never presses`() {
        val stick = DragStick(full())
        repeat(200) { assertEquals(DragStick.Action.NONE, stick.step(0.05f, 0.03f, it * 16L).action) }
        assertFalse(stick.isDown)
    }

    @Test fun `letting go returns the finger to centre before lifting`() {
        // Otherwise the view keeps turning for the whole hold delay after the
        // stick has been released.
        val c = full()
        val stick = DragStick(c)
        stick.step(1f, 0f, 0)
        stick.step(1f, 0f, 16)

        val settling = stick.step(0f, 0f, 32)
        assertEquals(DragStick.Action.MOVE, settling.action)
        assertEquals(0.5f, settling.x, 0.0001f)
        assertTrue("still down during the hold", stick.isDown)
    }

    @Test fun `a brief pause does not cost the press`() {
        val c = full()
        val stick = DragStick(c)
        stick.step(1f, 0f, 0)
        stick.step(0f, 0f, 10)
        assertTrue(stick.isDown)
    }

    @Test fun `letting go properly lifts, once`() {
        // The lift is one event, not a state the ticks keep repeating — a
        // second lift would be a second finger coming up that never went down.
        val c = full()
        val stick = DragStick(c)
        stick.step(1f, 0f, 0)

        var lifts = 0
        var t = 10L
        while (t < c.holdMs + 200) {
            if (stick.step(0f, 0f, t).action == DragStick.Action.LIFT) lifts++
            t += 16
        }

        assertEquals(1, lifts)
        assertFalse(stick.isDown)
    }

    @Test fun `inverting Y flips only Y`() {
        val normal = DragStick(full()).offsetFor(1f, 1f)
        val inverted = DragStick(AimSettings(invertY = true)).offsetFor(1f, 1f)

        assertEquals(normal.first, inverted.first, 0.0001f)
        assertTrue(normal.second > 0.5f)
        assertTrue(inverted.second < 0.5f)
    }

    @Test fun `a region pinned to one side still centres inside itself`() {
        val stick = DragStick(
            AimSettings(regionLeft = 0f, regionRight = 0.4f, regionTop = 0.6f, regionBottom = 1f)
        )
        val (cx, cy) = stick.centre()
        assertEquals(0.2f, cx, 0.0001f)
        assertEquals(0.8f, cy, 0.0001f)
        assertEquals(0.4f, stick.offsetFor(1f, 0f).first, 0.001f)
    }
}
