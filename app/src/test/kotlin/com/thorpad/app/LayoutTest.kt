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
}
