package com.thorpad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StripTest {

    private fun strip(
        slots: Int = 5,
        vertical: Boolean = false,
        x: Float = 0.5f,
        y: Float = 0.9f,
        width: Float = 0.8f,
        height: Float = 0.1f,
    ) = Control(
        id = "squad", label = "squad", keyCode = 0, x = x, y = y,
        kind = Kind.STRIP, slots = slots, vertical = vertical,
        width = width, height = height,
    )

    // ------------------------------------------------------------ positions

    @Test fun `slots are evenly spaced along the strip`() {
        val points = Strip.slotPoints(strip(slots = 5, width = 1f, x = 0.5f))
        val gaps = points.zipWithNext { a, b -> b.first - a.first }

        assertEquals(5, points.size)
        for (gap in gaps) assertEquals(0.2f, gap, 0.0001f)
    }

    @Test fun `the first slot is inside the strip, not on its end`() {
        // Dividing at the boundaries would put the first tap on the very edge
        // of the bar, which in a game is usually the frame rather than the
        // thing in it.
        val points = Strip.slotPoints(strip(slots = 5, width = 1f, x = 0.5f))
        assertTrue("first at ${points.first().first}", points.first().first > 0.05f)
        assertTrue("last at ${points.last().first}", points.last().first < 0.95f)
    }

    @Test fun `a horizontal strip keeps every slot on one line`() {
        val points = Strip.slotPoints(strip(y = 0.9f))
        for ((_, y) in points) assertEquals(0.9f, y, 0.0001f)
    }

    @Test fun `a vertical strip runs down the screen instead`() {
        val points = Strip.slotPoints(strip(vertical = true, x = 0.92f, height = 0.6f, y = 0.5f))

        for ((x, _) in points) assertEquals(0.92f, x, 0.0001f)
        val gaps = points.zipWithNext { a, b -> b.second - a.second }
        for (gap in gaps) assertEquals(0.12f, gap, 0.0001f)
    }

    @Test fun `slots follow the strip when it is moved`() {
        val left = Strip.slotPoints(strip(x = 0.3f, width = 0.4f))
        val right = Strip.slotPoints(strip(x = 0.7f, width = 0.4f))

        for (i in left.indices) {
            assertEquals(0.4f, right[i].first - left[i].first, 0.0001f)
        }
    }

    @Test fun `a shorter strip packs the same slots closer`() {
        val wide = Strip.slotPoints(strip(width = 0.9f))
        val narrow = Strip.slotPoints(strip(width = 0.3f))

        val wideGap = wide[1].first - wide[0].first
        val narrowGap = narrow[1].first - narrow[0].first
        assertTrue("$narrowGap should be well under $wideGap", narrowGap < wideGap / 2f)
    }

    @Test fun `nothing lands off the screen`() {
        for (x in listOf(0f, 0.5f, 1f)) {
            for (point in Strip.slotPoints(strip(x = x, width = 1f))) {
                assertTrue(point.first in 0f..1f)
                assertTrue(point.second in 0f..1f)
            }
        }
    }

    @Test fun `a silly slot count is clamped rather than obeyed`() {
        assertEquals(2, Strip.slotPoints(strip(slots = 0)).size)
        assertEquals(12, Strip.slotPoints(strip(slots = 99)).size)
    }

    // ------------------------------------------------------------- stepping

    @Test fun `the first press forward lands on the first slot`() {
        assertEquals(0, Strip.step(from = -1, delta = 1, slots = 5))
    }

    @Test fun `the first press back lands on the last`() {
        assertEquals(4, Strip.step(from = -1, delta = -1, slots = 5))
    }

    @Test fun `each press moves one along`() {
        var at = -1
        val walked = (0 until 5).map { Strip.step(at, 1, 5).also { next -> at = next } }
        assertEquals(listOf(0, 1, 2, 3, 4), walked)
    }

    @Test fun `it wraps rather than stopping at the end`() {
        // A squad bar is a ring in practice — one shoulder button should get
        // you all the way round rather than making you reach for the other.
        assertEquals(0, Strip.step(from = 4, delta = 1, slots = 5))
        assertEquals(4, Strip.step(from = 0, delta = -1, slots = 5))
    }

    @Test fun `stepping is reversible`() {
        for (start in 0 until 5) {
            assertEquals(start, Strip.step(Strip.step(start, 1, 5), -1, 5))
        }
    }

    @Test fun `a full loop comes back to where it started`() {
        var at = 0
        repeat(5) { at = Strip.step(at, 1, 5) }
        assertEquals(0, at)
    }

    // -------------------------------------------------------------- binding

    @Test fun `a strip answers to both of its buttons, each with a direction`() {
        val layout = Layout().add(
            strip().copy(keyCode = 103, keyCodePrev = 102)
        )

        assertEquals(1, layout.stripFor(103)?.second)
        assertEquals(-1, layout.stripFor(102)?.second)
        assertNull(layout.stripFor(99))
    }

    @Test fun `one direction bound is enough to be useful`() {
        // A short strip you only ever cycle forward through needs one button.
        val layout = Layout().add(strip().copy(keyCode = 103))
        assertTrue(layout["squad"]!!.bound)
        assertNull(layout.stripFor(102))
    }

    @Test fun `a strip is never fired as a plain button`() {
        val layout = Layout().add(strip().copy(keyCode = 103))
        assertNull("a strip steps; it does not tap one point", layout.forKey(103))
    }

    @Test fun `binding a button steals it from a strip direction`() {
        // Two controls on one button would fire both, and nothing on screen
        // would say which one you meant.
        var layout = Layout()
            .add(strip().copy(keyCode = 103, keyCodePrev = 102))
            .add(Control("fire", "fire", 0, 0.8f, 0.8f))

        layout = layout.bind("fire", 103)

        assertEquals(0, layout["squad"]!!.keyCode)
        assertEquals(102, layout["squad"]!!.keyCodePrev, )
        assertEquals("fire", layout.forKey(103)?.id)
    }

    @Test fun `binding one direction steals it from the other`() {
        var layout = Layout().add(strip().copy(keyCode = 103, keyCodePrev = 102))
        layout = layout.bindPrev("squad", 103)

        assertEquals(0, layout["squad"]!!.keyCode)
        assertEquals(103, layout["squad"]!!.keyCodePrev)
    }

    @Test fun `a strip survives being saved and loaded`() {
        val layout = Layout().add(
            strip(slots = 4, vertical = true).copy(keyCode = 103, keyCodePrev = 102)
        )
        val back = Layout.fromJson(layout.toJson())["squad"]!!

        assertEquals(Kind.STRIP, back.kind)
        assertEquals(4, back.slots)
        assertTrue(back.vertical)
        assertEquals(103, back.keyCode)
        assertEquals(102, back.keyCodePrev)
    }

    @Test fun `a nonsense slot count from storage is pulled into range`() {
        val json = """[{"id":"s","label":"s","keyCode":103,"x":0.5,"y":0.9,""" +
            """"kind":"STRIP","slots":900}]"""
        assertEquals(12, Layout.fromJson(json)["s"]!!.slots)
    }

    @Test fun `and the same the other way round`() {
        var layout = Layout().add(strip().copy(keyCode = 103, keyCodePrev = 102))
        layout = layout.bind("squad", 102)

        assertEquals(102, layout["squad"]!!.keyCode)
        assertEquals(0, layout["squad"]!!.keyCodePrev)
        assertEquals(1, layout.stripFor(102)?.second)
    }
}
