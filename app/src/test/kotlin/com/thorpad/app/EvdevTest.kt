package com.thorpad.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EvdevTest {

    private fun eventBytes(vararg events: Triple<Int, Int, Int>): ByteArray {
        val bb = ByteBuffer.allocate(Evdev.EVENT_SIZE * events.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        for ((type, code, value) in events) {
            bb.putLong(0); bb.putLong(0)
            bb.putShort(type.toShort())
            bb.putShort(code.toShort())
            bb.putInt(value)
        }
        return bb.array()
    }

    @Test fun `parses a run of events`() {
        val bytes = eventBytes(
            Triple(Evdev.EV_ABS, Evdev.ABS_RX, 12345),
            Triple(Evdev.EV_ABS, Evdev.ABS_RY, -6000),
            Triple(Evdev.EV_KEY, Evdev.BTN_THUMBR, 1),
        )
        val events = Evdev.parse(bytes, bytes.size)

        assertEquals(3, events.size)
        assertEquals(Evdev.Event(Evdev.EV_ABS, Evdev.ABS_RX, 12345), events[0])
        assertEquals(-6000, events[1].value)
        assertEquals(1, events[2].value)
    }

    @Test fun `a read that lands mid-event ignores the tail`() {
        // Treating a partial record as complete is how you get a phantom axis
        // slammed to an extreme for one frame.
        val bytes = eventBytes(Triple(Evdev.EV_ABS, Evdev.ABS_RX, 900))
        val truncated = bytes + ByteArray(9)

        val events = Evdev.parse(truncated, truncated.size)

        assertEquals(1, events.size)
        assertEquals(900, events[0].value)
    }

    @Test fun `codes above 32767 survive the unsigned round trip`() {
        // Button codes live at 0x130+, which fits, but ABS_MT codes and the
        // higher KEY range do not fit a signed short.
        val bytes = eventBytes(Triple(Evdev.EV_KEY, 0xFFF0, 1))
        val events = Evdev.parse(bytes, bytes.size)
        assertEquals(0xFFF0, events[0].code)
    }

    // ------------------------------------------------------------- bitmaps

    @Test fun `a capability bitmap is most significant word first`() {
        // The last word holds bits 0..63. Read the other way round, a pad's
        // buttons land in the tens of thousands and it looks like it has none.
        assertEquals(setOf(0), Evdev.decodeBitmap("1"))
        assertEquals(setOf(0, 1, 2), Evdev.decodeBitmap("7"))
        assertEquals(setOf(64), Evdev.decodeBitmap("1 0"))
        assertEquals(setOf(0, 64), Evdev.decodeBitmap("1 1"))
    }

    @Test fun `a real gamepad button bitmap decodes into the BTN range`() {
        // BTN_SOUTH is 0x130 = 304, which is bit 304: word index 4 from the
        // bottom, bit 48 within it.
        val bits = Evdev.decodeBitmap("7cdb000000000000 0 0 0 0")
        assertTrue("BTN_SOUTH should be present", bits.any { it in 0x130..0x13F })
    }

    @Test fun `nonsense in the bitmap is skipped rather than thrown`() {
        assertEquals(emptySet<Int>(), Evdev.decodeBitmap(""))
        assertEquals(emptySet<Int>(), Evdev.decodeBitmap("zzz"))
        assertEquals(setOf(0), Evdev.decodeBitmap("zzz 1"))
    }

    // --------------------------------------------------------- stick choice

    @Test fun `a pad reporting RX and RY uses them`() {
        val device = Evdev.Device(
            "/dev/input/event8", "pad",
            absCodes = setOf(Evdev.ABS_X, Evdev.ABS_Y, Evdev.ABS_RX, Evdev.ABS_RY),
            keyCodes = setOf(Evdev.BTN_SOUTH),
        )
        assertEquals(Evdev.ABS_RX to Evdev.ABS_RY, device.rightStick)
        assertTrue(device.looksLikeGamepad)
    }

    @Test fun `a pad that puts the right stick on Z and RZ is still supported`() {
        val device = Evdev.Device(
            "/dev/input/event8", "pad",
            absCodes = setOf(Evdev.ABS_X, Evdev.ABS_Y, Evdev.ABS_Z, Evdev.ABS_RZ),
            keyCodes = setOf(Evdev.BTN_SOUTH),
        )
        assertEquals(Evdev.ABS_Z to Evdev.ABS_RZ, device.rightStick)
        assertTrue(device.looksLikeGamepad)
    }

    @Test fun `a touchscreen is not mistaken for a gamepad`() {
        val touch = Evdev.Device(
            "/dev/input/event3", "ft5x06",
            absCodes = setOf(0x35, 0x36, 0x39),
            keyCodes = setOf(0x14A),
        )
        assertFalse(touch.looksLikeGamepad)
    }

    @Test fun `a keyboard is not mistaken for a gamepad`() {
        val keyboard = Evdev.Device(
            "/dev/input/event1", "gpio-keys",
            absCodes = emptySet(),
            keyCodes = setOf(30, 31, 32),
        )
        assertFalse(keyboard.looksLikeGamepad)
    }

    // ---------------------------------------------------------- axis ranges

    @Test fun `a range read off the device is used as given`() {
        val range = AxisRange()
        range.set(0, 255)

        assertTrue(range.known)
        assertEquals(0.0, range.normalise(128).toDouble(), 0.01)
        assertEquals(1.0, range.normalise(255).toDouble(), 0.01)
        assertEquals(-1.0, range.normalise(0).toDouble(), 0.01)
    }

    @Test fun `a narrow range cannot be discovered by watching`() {
        // The reason ranges are read from the device rather than inferred: a
        // 0..255 pad sits entirely inside an assumed signed-16-bit range, so
        // every reading normalises to about zero and the stick looks dead —
        // and nothing ever falls outside the assumption to give it away.
        val guessing = AxisRange()
        guessing.observe(0)
        guessing.observe(128)
        guessing.observe(255)

        assertFalse("nothing was learned", guessing.known)
        assertTrue(
            "full deflection still reads as barely moved",
            guessing.normalise(255) < 0.01f,
        )
    }

    @Test fun `a pad that over-ranges its own claim is widened`() {
        // The case watching *can* catch: values beyond what the device said.
        val range = AxisRange()
        range.set(-1000, 1000)
        range.observe(-1400)
        range.observe(1400)

        assertEquals(-1400, range.minimum)
        assertEquals(1400, range.maximum)
        assertEquals(1.0, range.normalise(1400).toDouble(), 0.01)
    }

    @Test fun `an inside-out range from the device is refused`() {
        val range = AxisRange()
        range.set(100, 100)
        assertEquals(-32768, range.minimum)
        assertFalse(range.known)
    }

    @Test fun `an unread range says so`() {
        assertTrue(AxisRange().toString().contains("assumed"))
        assertFalse(AxisRange().apply { set(0, 255) }.toString().contains("assumed"))
    }
}
