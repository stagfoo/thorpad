package com.thorpad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {

    private fun control(id: String, key: Int = 0, x: Float = 0.5f, y: Float = 0.5f) =
        Control(id = id, label = id, keyCode = key, x = x, y = y)

    @Test fun `a button fires the control it is bound to`() {
        val layout = Layout()
            .add(control("fire", key = 96))
            .add(control("reload", key = 99))

        assertEquals("fire", layout.forKey(96)?.id)
        assertEquals("reload", layout.forKey(99)?.id)
    }

    @Test fun `an unbound control is fired by nothing`() {
        val layout = Layout().add(control("spare"))
        assertNull(layout.forKey(0))
        assertFalse(layout["spare"]!!.bound)
    }

    @Test fun `binding a button takes it off whatever had it`() {
        // Two controls on one button would fire both, and nothing on screen
        // would say which one you meant.
        var layout = Layout()
            .add(control("fire", key = 96))
            .add(control("reload"))

        layout = layout.bind("reload", 96)

        assertEquals("reload", layout.forKey(96)?.id)
        assertFalse("the old owner is left unbound", layout["fire"]!!.bound)
    }

    @Test fun `rebinding a control to the button it already has changes nothing`() {
        val layout = Layout().add(control("fire", key = 96)).bind("fire", 96)
        assertEquals(96, layout["fire"]!!.keyCode)
        assertEquals(1, layout.controls.size)
    }

    @Test fun `binding an id that does not exist is a no-op`() {
        val layout = Layout().add(control("fire", key = 96))
        assertEquals(layout, layout.bind("ghost", 99))
    }

    @Test fun `a control cannot be dragged off the screen`() {
        val moved = control("fire").movedTo(2f, -1f)
        assertEquals(1f, moved.x, 0.001f)
        assertEquals(0f, moved.y, 0.001f)
    }

    @Test fun `a layout survives being saved and loaded`() {
        val layout = Layout()
            .add(control("fire", key = 96, x = 0.8f, y = 0.6f))
            .add(Control("aim", "aim", 99, 0.2f, 0.3f, Press.HOLD))

        val restored = Layout.fromJson(layout.toJson())

        assertEquals(2, restored.controls.size)
        assertEquals(0.8f, restored["fire"]!!.x, 0.001f)
        assertEquals(Press.HOLD, restored["aim"]!!.press)
        assertEquals("aim", restored.forKey(99)?.id)
    }

    @Test fun `nonsense in storage loads as an empty layout rather than crashing`() {
        // On launch there is nothing else to fall back to, and a crash here
        // means an app that cannot be opened to fix it.
        assertEquals(0, Layout.fromJson("{oh dear").controls.size)
        assertEquals(0, Layout.fromJson("").controls.size)
        assertEquals(0, Layout.fromJson(null).controls.size)
    }

    @Test fun `a control missing its id is dropped, not loaded half-formed`() {
        val json = """[{"label":"nameless","x":0.5,"y":0.5}]"""
        assertEquals(0, Layout.fromJson(json).controls.size)
    }

    @Test fun `a position outside the screen is pulled back in on load`() {
        val json = """[{"id":"a","label":"a","keyCode":96,"x":5.0,"y":-2.0}]"""
        val control = Layout.fromJson(json)["a"]!!
        assertEquals(1f, control.x, 0.001f)
        assertEquals(0f, control.y, 0.001f)
    }

    @Test fun `an unknown press kind falls back to a tap`() {
        val json = """[{"id":"a","label":"a","keyCode":96,"x":0.5,"y":0.5,"press":"WAGGLE"}]"""
        assertEquals(Press.TAP, Layout.fromJson(json)["a"]!!.press)
    }

    @Test fun `removing a control leaves the rest alone`() {
        val layout = Layout()
            .add(control("a", key = 96))
            .add(control("b", key = 97))
            .remove("a")

        assertNull(layout["a"])
        assertEquals("b", layout.forKey(97)?.id)
    }

    @Test fun `ids are unique enough not to collide when added in a burst`() {
        var layout = Layout()
        repeat(8) { layout = layout.add(control(Layout.nextId(layout))) }
        assertEquals(8, layout.controls.map { it.id }.toSet().size)
    }

    // ---------------------------------------------------------------- sticks

    private fun stick(id: String, s: Stick? = null, x: Float = 0.5f, y: Float = 0.5f) =
        Control(id = id, label = id, keyCode = 0, x = x, y = y,
            kind = Kind.STICK, stick = s)

    @Test fun `a stick control is bound by a stick, not a key`() {
        val layout = Layout().add(stick("aim", Stick.RIGHT))
        assertTrue(layout["aim"]!!.bound)
        assertEquals(1, layout.sticks().size)
    }

    @Test fun `an unassigned stick control is not bound`() {
        assertFalse(Layout().add(stick("aim"))["aim"]!!.bound)
        assertEquals(0, Layout().add(stick("aim")).sticks().size)
    }

    @Test fun `binding a stick takes it off whatever had it`() {
        // Two controls on one stick would drag two fingers from one thumb.
        var layout = Layout().add(stick("aim", Stick.RIGHT)).add(stick("move"))
        layout = layout.bindStick("move", Stick.RIGHT)

        assertEquals(Stick.RIGHT, layout["move"]!!.stick)
        assertNull("the old owner is released", layout["aim"]!!.stick)
        assertEquals(1, layout.sticks().size)
    }

    @Test fun `a stick control is never fired by a key`() {
        // Its keyCode field is meaningless; matching on it would make a stick
        // fire a tap every time that button was pressed.
        val layout = Layout().add(
            Control("aim", "aim", 96, 0.5f, 0.5f, kind = Kind.STICK, stick = Stick.RIGHT)
        )
        assertNull(layout.forKey(96))
    }

    @Test fun `binding a key does not disturb a stick that shares the number`() {
        var layout = Layout()
            .add(Control("aim", "aim", 96, 0.5f, 0.5f, kind = Kind.STICK, stick = Stick.RIGHT))
            .add(control("fire"))

        layout = layout.bind("fire", 96)

        assertEquals(Stick.RIGHT, layout["aim"]!!.stick)
        assertEquals("fire", layout.forKey(96)?.id)
    }

    // ---------------------------------------------------------------- region

    @Test fun `a full-size region is the whole screen`() {
        val region = stick("aim", Stick.RIGHT).region()
        assertEquals(0f, region.left, 0.001f)
        assertEquals(1f, region.right, 0.001f)
        assertEquals(0f, region.top, 0.001f)
        assertEquals(1f, region.bottom, 0.001f)
    }

    @Test fun `a region near an edge is shifted, not squashed`() {
        // Squashing would quietly cost travel, which is the one thing a region
        // exists to provide.
        val control = stick("aim", Stick.RIGHT, x = 0.05f, y = 0.5f)
            .copy(width = 0.5f, height = 0.5f)
        val region = control.region()

        assertEquals(0f, region.left, 0.001f)
        assertEquals(0.5f, region.right - region.left, 0.001f)
    }

    @Test fun `a region is never wider than the screen`() {
        val control = stick("aim", Stick.RIGHT).copy(width = 1f, height = 1f)
        val region = control.region()
        assertTrue(region.right - region.left <= 1.001f)
        assertTrue(region.bottom - region.top <= 1.001f)
    }

    @Test fun `a stick survives being saved and loaded`() {
        val layout = Layout().add(
            stick("aim", Stick.RIGHT, x = 0.4f, y = 0.6f).copy(width = 0.8f)
        )
        val restored = Layout.fromJson(layout.toJson())["aim"]!!

        assertEquals(Kind.STICK, restored.kind)
        assertEquals(Stick.RIGHT, restored.stick)
        assertEquals(0.8f, restored.width, 0.001f)
    }

    @Test fun `a layout from before sticks existed still loads as buttons`() {
        // Written by an earlier version: no kind, no stick, no size.
        val json = """[{"id":"fire","label":"fire","keyCode":96,"x":0.8,"y":0.6}]"""
        val control = Layout.fromJson(json)["fire"]!!

        assertEquals(Kind.BUTTON, control.kind)
        assertTrue(control.bound)
        assertEquals(1f, control.width, 0.001f)
    }
}
