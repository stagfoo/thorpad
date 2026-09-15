package com.thorpad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The aim maths, checked against numbers.
 *
 * Every claim here is one about feel that can be stated as arithmetic: how far
 * one swing travels, how slow the slowest useful movement is, how often a sweep
 * has to restart. The device is still needed to pick the constants — it is not
 * needed to find out whether the maths does what it says.
 */
class AimEngineTest {

    /** How a sweep went: total distance, longest unbroken stroke, hitches. */
    private data class Sweep(
        val distance: Float,
        val longest: Float,
        val restarts: Int,
        val presses: Int,
    )

    private fun run(c: AimSettings, sx: Float, sy: Float, seconds: Float): Sweep {
        val engine = AimEngine(c)
        val dt = 1f / c.pollHz
        var distance = 0f
        var longest = 0f
        var stroke = 0f
        var restarts = 0
        var presses = 0
        var lastX = 0f
        var lastY = 0f
        var haveLast = false

        var t = 0f
        while (t < seconds) {
            val step = engine.step(sx, sy, dt, (t * 1000).toLong())
            when (step.action) {
                AimEngine.Action.PRESS -> {
                    presses++
                    stroke = 0f
                    lastX = step.x; lastY = step.y; haveLast = true
                }
                AimEngine.Action.RESTART -> {
                    restarts++
                    if (stroke > longest) longest = stroke
                    stroke = 0f
                    lastX = step.x; lastY = step.y
                }
                AimEngine.Action.MOVE -> {
                    if (haveLast) {
                        val d = hypot(step.x - lastX, step.y - lastY)
                        stroke += d
                        distance += d
                    }
                    lastX = step.x; lastY = step.y; haveLast = true
                }
                else -> Unit
            }
            t += dt
        }
        if (stroke > longest) longest = stroke
        return Sweep(distance, longest, restarts, presses)
    }

    // ------------------------------------------------------------- deadzone

    @Test fun `a stick inside the deadzone does nothing`() {
        val engine = AimEngine(AimSettings())
        val step = engine.step(0.05f, 0f, 0.01f, 0)
        assertEquals(AimEngine.Action.NONE, step.action)
        assertFalse(engine.isDown)
    }

    @Test fun `a resting stick that drifts never starts aiming`() {
        // A pad that rests at 0.05 would otherwise aim slowly off target for
        // ever, which is the single most obvious way this could be unusable.
        val engine = AimEngine(AimSettings())
        repeat(500) { engine.step(0.05f, 0.03f, 0.01f, it * 10L) }
        assertFalse(engine.isDown)
    }

    // -------------------------------------------------------- response curve

    @Test fun `full tilt is far faster than a nudge past the deadzone`() {
        val c = AimSettings()
        val nudge = run(c, c.deadzone + 0.02f, 0f, 1f)
        val full = run(c, 1f, 0f, 1f)

        assertTrue("a nudge does move", nudge.distance > 0f)
        assertTrue(
            "full tilt (${full.distance}) should dwarf a nudge (${nudge.distance})",
            full.distance > nudge.distance * 20f,
        )
    }

    @Test fun `a second at full tilt covers the configured top speed`() {
        val c = AimSettings()
        val sweep = run(c, 1f, 0f, 1f)
        assertEquals(c.maxSpeed.toDouble(), sweep.distance.toDouble(), (c.maxSpeed * 0.05).toDouble())
    }

    @Test fun `a corner push is no faster than a straight one`() {
        val c = AimSettings()
        val straight = run(c, 1f, 0f, 1f)
        val corner = run(c, 1f, 1f, 1f)
        assertEquals(
            straight.distance.toDouble(),
            corner.distance.toDouble(),
            (straight.distance * 0.05).toDouble(),
        )
    }

    // ---------------------------------------------------------------- travel

    @Test fun `one stroke crosses most of the screen`() {
        val sweep = run(AimSettings(), 1f, 0f, 3f)
        assertTrue(
            "longest stroke was ${sweep.longest} screens",
            sweep.longest > 0.8f,
        )
    }

    @Test fun `starting from the far edge beats starting centred`() {
        // The whole point. A stroke that starts in the middle only ever gets
        // half the screen, however big the region is.
        val edge = run(AimSettings(), 1f, 0f, 3f)
        val centred = run(AimSettings(anchorBias = 0f), 1f, 0f, 3f)

        assertTrue(
            "edge ${edge.longest} vs centred ${centred.longest}",
            edge.longest > centred.longest * 1.6f,
        )
        assertTrue(
            "edge hitched ${edge.restarts} times, centred ${centred.restarts}",
            edge.restarts < centred.restarts,
        )
    }

    @Test fun `the full screen beats a small box`() {
        val full = run(AimSettings(), 1f, 0f, 3f)
        val box = run(
            AimSettings(regionLeft = 0.3f, regionRight = 0.7f, regionTop = 0.3f, regionBottom = 0.7f),
            1f, 0f, 3f,
        )

        assertTrue(
            "full ${full.longest} vs box ${box.longest}",
            full.longest > box.longest * 2f,
        )
        assertTrue(full.restarts < box.restarts)
    }

    @Test fun `the anchor faces away from the direction of travel`() {
        val c = AimSettings()

        val right = AimEngine.anchorFor(c, 1f, 0f)
        assertTrue("swinging right starts left: ${right.first}", right.first < 0.2f)
        assertEquals(0.5, right.second.toDouble(), 0.01)

        val left = AimEngine.anchorFor(c, -1f, 0f)
        assertTrue("swinging left starts right: ${left.first}", left.first > 0.8f)

        val down = AimEngine.anchorFor(c, 0f, 1f)
        assertTrue("swinging down starts high: ${down.second}", down.second < 0.2f)
        assertEquals(0.5, down.first.toDouble(), 0.01)
    }

    @Test fun `no direction means the middle, not a divide by zero`() {
        val middle = AimEngine.anchorFor(AimSettings(), 0f, 0f)
        assertEquals(0.5, middle.first.toDouble(), 0.001)
        assertEquals(0.5, middle.second.toDouble(), 0.001)
    }

    // ----------------------------------------------------------- holding on

    @Test fun `a brief pause mid-sweep does not cost the stroke`() {
        val c = AimSettings()
        val engine = AimEngine(c)
        val dt = 1f / c.pollHz
        var t = 0L

        repeat(20) { engine.step(0.6f, 0f, dt, t); t += 8 }
        assertTrue("finger is down mid-sweep", engine.isDown)

        repeat(5) { engine.step(0f, 0f, dt, t); t += 8 }
        assertTrue("a brief pause keeps it down", engine.isDown)
    }

    @Test fun `letting go properly does lift the finger`() {
        val c = AimSettings()
        val engine = AimEngine(c)
        val dt = 1f / c.pollHz
        var t = 0L

        repeat(20) { engine.step(0.6f, 0f, dt, t); t += 8 }
        val until = t + c.holdMs + 50
        while (t < until) { engine.step(0f, 0f, dt, t); t += 8 }

        assertFalse(engine.isDown)
    }

    // ------------------------------------------------------------ axis range

    @Test fun `axis ranges are read from the device, not assumed`() {
        // Pads report wildly different ranges; hardcoding signed 16-bit would
        // make an 8-bit pad permanently pinned to one corner.
        assertEquals(1.0, AimEngine.normalise(32767, -32768, 32767).toDouble(), 0.001)
        assertEquals(0.0, AimEngine.normalise(0, -32768, 32767).toDouble(), 0.001)
        assertEquals(1.0, AimEngine.normalise(255, 0, 255).toDouble(), 0.001)
        assertEquals(0.0, AimEngine.normalise(128, 0, 255).toDouble(), 0.01)
        assertEquals(-1.0, AimEngine.normalise(0, 0, 255).toDouble(), 0.001)
    }

    @Test fun `a broken range reads as centred rather than crashing`() {
        assertEquals(0.0, AimEngine.normalise(0, 0, 0).toDouble(), 0.001)
        assertEquals(0.0, AimEngine.normalise(500, 10, 10).toDouble(), 0.001)
    }

    @Test fun `a reading beyond the reported range clamps`() {
        assertEquals(1.0, AimEngine.normalise(99999, -32768, 32767).toDouble(), 0.001)
        assertEquals(-1.0, AimEngine.normalise(-99999, -32768, 32767).toDouble(), 0.001)
    }

    // ----------------------------------------------------------- reconfigure

    @Test fun `settings can change mid-sweep without dropping the finger`() {
        // Tuning happens against live gameplay, so a slider must not cost the
        // stroke that is currently down.
        val engine = AimEngine(AimSettings())
        val dt = 1f / 120
        repeat(20) { engine.step(0.6f, 0f, dt, it * 8L) }
        assertTrue(engine.isDown)

        engine.reconfigure(AimSettings(maxSpeed = 4f))
        val step = engine.step(0.6f, 0f, dt, 200)

        assertTrue(engine.isDown)
        assertEquals(AimEngine.Action.MOVE, step.action)
    }

    /** Prints the table the README quotes, so it cannot drift from the code. */
    @Test fun `travel table`() {
        val rows = listOf(
            "full screen, far edge" to AimSettings(),
            "full screen, centred" to AimSettings(anchorBias = 0f),
            "40% box, centred" to AimSettings(
                anchorBias = 0f,
                regionLeft = 0.3f, regionRight = 0.7f,
                regionTop = 0.3f, regionBottom = 0.7f,
            ),
        )
        println("  setup                  longest stroke   restarts/3s")
        for ((name, c) in rows) {
            val s = run(c, 1f, 0f, 3f)
            println("  %-22s %.3f            %d".format(name, s.longest, s.restarts))
        }
    }
}
