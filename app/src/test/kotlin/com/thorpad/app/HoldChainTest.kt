package com.thorpad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The rule the hold chain kept breaking, as arithmetic.
 *
 * A continued gesture stroke must begin exactly where the previous one ended.
 * The jitter moved the finger without recording it, so every stationary segment
 * left the written-down position a nudge behind the real one — and the moment
 * the crosshair moved, the continuation started somewhere the previous stroke
 * had not finished, and the gesture API threw.
 *
 * The chain itself needs Android, but the walk does not, so the walk is here.
 */
class HoldChainTest {

    /** The same stepping the Hold does: aim, wander if still, record the end. */
    private class Walk(var atX: Float, var atY: Float, val jitter: Float) {
        var wantX = atX
        var wantY = atY
        private var wander = 1f

        /** Returns (startX, startY, endX, endY) for one segment. */
        fun step(): List<Float> {
            val fromX = atX
            val fromY = atY
            var toX = wantX
            var toY = wantY
            if (toX == atX && toY == atY) {
                wander = -wander
                val nudge = if (jitter <= 0f) 0.1f else jitter
                toX = atX + nudge * wander
                toY = atY
            }
            atX = toX
            atY = toY
            return listOf(fromX, fromY, toX, toY)
        }
    }

    @Test fun `every segment starts where the last one ended`() {
        val walk = Walk(500f, 800f, jitter = 2f)
        var lastEndX = 500f
        var lastEndY = 800f

        // Stand still, then sweep, then stand still again — the sequence that
        // crashed: jitter, jitter, move.
        repeat(30) { i ->
            if (i in 10..20) {
                walk.wantX = 500f + (i - 10) * 12f
                walk.wantY = 800f - (i - 10) * 4f
            }
            val (startX, startY, endX, endY) = walk.step()
            assertEquals("segment $i starts adrift in x", lastEndX, startX, 0.0001f)
            assertEquals("segment $i starts adrift in y", lastEndY, startY, 0.0001f)
            lastEndX = endX
            lastEndY = endY
        }
    }

    @Test fun `a standing finger still moves every segment`() {
        // A stroke with identical ends is rejected as empty, and a finger that
        // never moves reads to some engines as one that has stopped.
        val walk = Walk(100f, 100f, jitter = 2f)
        repeat(8) {
            val (sx, sy, ex, ey) = walk.step()
            assertTrue("segment did not move", abs(ex - sx) + abs(ey - sy) > 0f)
        }
    }

    @Test fun `a standing finger stays within a fingertip of where it was put`() {
        val walk = Walk(100f, 100f, jitter = 2f)
        repeat(200) { walk.step() }
        assertTrue(
            "drifted to ${walk.atX}",
            abs(walk.atX - 100f) <= 2.001f,
        )
    }

    @Test fun `jitter turned off still produces a real segment`() {
        val walk = Walk(100f, 100f, jitter = 0f)
        val (sx, _, ex, _) = walk.step()
        assertTrue(abs(ex - sx) > 0f)
    }

    @Test fun `a sweep goes where it was aimed, not where jitter left it`() {
        val walk = Walk(100f, 100f, jitter = 2f)
        walk.step()            // one stationary jitter
        walk.wantX = 400f
        walk.wantY = 250f
        val (_, _, ex, ey) = walk.step()

        assertEquals(400f, ex, 0.0001f)
        assertEquals(250f, ey, 0.0001f)
    }
}
