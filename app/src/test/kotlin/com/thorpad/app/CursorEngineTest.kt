package com.thorpad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorEngineTest {

    private fun run(
        c: AimSettings,
        sx: Float,
        sy: Float,
        seconds: Float,
    ): CursorEngine {
        val cursor = CursorEngine(c).apply { centre() }
        val dt = 1f / c.pollHz
        var t = 0f
        while (t < seconds) {
            cursor.step(sx, sy, dt)
            t += dt
        }
        return cursor
    }

    @Test fun `a resting stick does not move the crosshair`() {
        // A pad that rests slightly off centre would otherwise walk the
        // crosshair off the screen while nobody was touching it.
        val cursor = CursorEngine(AimSettings()).apply { centre() }
        repeat(500) { assertFalse(cursor.step(0.05f, 0.03f, 0.01f)) }
        assertEquals(0.5f, cursor.x, 0.0001f)
    }

    @Test fun `full tilt crosses the screen at the configured speed`() {
        // 2.2 screens a second means half a second crosses 1.1 screens, which
        // the clamp cuts at the edge — so check a shorter push.
        val cursor = run(AimSettings(), 1f, 0f, 0.2f)
        assertEquals(0.5f + 2.2f * 0.2f, cursor.x, 0.02f)
    }

    @Test fun `a nudge past the deadzone is far slower than full tilt`() {
        val c = AimSettings()
        val nudged = run(c, c.deadzone + 0.02f, 0f, 0.2f)
        val shoved = run(c, 1f, 0f, 0.2f)

        assertTrue(nudged.x > 0.5f)
        assertTrue(
            "nudge ${nudged.x} vs full ${shoved.x}",
            (shoved.x - 0.5f) > (nudged.x - 0.5f) * 20f,
        )
    }

    @Test fun `a corner push is no faster than a straight one`() {
        val straight = run(AimSettings(), 1f, 0f, 0.15f)
        val corner = run(AimSettings(), 1f, 1f, 0.15f)

        val straightMoved = straight.x - 0.5f
        val cornerMoved = kotlin.math.hypot(corner.x - 0.5f, corner.y - 0.5f)
        assertEquals(straightMoved.toDouble(), cornerMoved.toDouble(), 0.02)
    }

    @Test fun `the crosshair stops at the edge rather than wrapping`() {
        // Stopping is what lets you push against an edge and hold steady,
        // which is most of aiming at the edge of a screen.
        val cursor = run(AimSettings(), 1f, 0f, 3f)
        assertEquals(1f, cursor.x, 0.0001f)

        val back = run(AimSettings(), -1f, 0f, 3f)
        assertEquals(0f, back.x, 0.0001f)
    }

    @Test fun `it stays inside a region smaller than the screen`() {
        val c = AimSettings(
            regionLeft = 0.25f, regionRight = 0.75f,
            regionTop = 0.25f, regionBottom = 0.75f,
        )
        val cursor = run(c, 1f, 1f, 3f)
        assertEquals(0.75f, cursor.x, 0.0001f)
        assertEquals(0.75f, cursor.y, 0.0001f)
    }

    @Test fun `centring puts it in the middle of its own region, not the screen`() {
        val cursor = CursorEngine(
            AimSettings(regionLeft = 0.5f, regionRight = 1f, regionTop = 0f, regionBottom = 0.5f)
        ).apply { centre() }

        assertEquals(0.75f, cursor.x, 0.0001f)
        assertEquals(0.25f, cursor.y, 0.0001f)
    }

    @Test fun `inverting Y flips it and nothing else`() {
        val normal = run(AimSettings(), 0f, 1f, 0.1f)
        val inverted = run(AimSettings(invertY = true), 0f, 1f, 0.1f)

        assertTrue(normal.y > 0.5f)
        assertTrue(inverted.y < 0.5f)
        assertEquals(normal.x, inverted.x, 0.0001f)
    }

    @Test fun `it reports whether anything actually changed`() {
        // So a screen that has not changed is not redrawn sixty times a second.
        val cursor = CursorEngine(AimSettings()).apply { centre() }
        assertFalse(cursor.step(0f, 0f, 0.016f))
        assertTrue(cursor.step(1f, 0f, 0.016f))
    }

    @Test fun `pressed against an edge it stops reporting movement`() {
        val cursor = CursorEngine(AimSettings()).apply { centre() }
        repeat(400) { cursor.step(1f, 0f, 0.016f) }
        assertFalse("already at the edge", cursor.step(1f, 0f, 0.016f))
    }
}
