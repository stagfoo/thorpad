package com.thorpad.app

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reading the gamepad from the kernel, underneath Android's input pipeline.
 *
 * This is the whole reason the thing can work at all while a game is in front.
 * Android delivers key and motion events to the *focused* window, so nothing
 * running in the background sees a stick through the normal API. `/dev/input`
 * sits below that: the kernel has no idea what focus is, and every reader with
 * permission sees every event. `adb shell getevent` printing your stick while a
 * game is foreground is the same mechanism.
 *
 * Permission is the only catch — those nodes are `crw-rw---- root input`, and
 * an ordinary app uid is not in the `input` group. Shell is, which is why
 * getevent works over ADB and why this has to run inside a Shizuku service.
 */
object Evdev {

    /** `struct input_event` on a 64-bit kernel: two 8-byte times, u16, u16, s32. */
    const val EVENT_SIZE = 24

    const val EV_KEY = 0x01
    const val EV_ABS = 0x03

    const val ABS_X = 0x00
    const val ABS_Y = 0x01
    const val ABS_Z = 0x02
    const val ABS_RX = 0x03
    const val ABS_RY = 0x04
    const val ABS_RZ = 0x05

    const val BTN_SOUTH = 0x130
    const val BTN_THUMBL = 0x13D
    const val BTN_THUMBR = 0x13E

    data class Device(
        val path: String,
        val name: String,
        val absCodes: Set<Int>,
        val keyCodes: Set<Int>,
    ) {
        /**
         * Which pair of axes is the right stick on this pad.
         *
         * Pads disagree: some report RX/RY, plenty of others put the right
         * stick on Z/RZ. Picking by what the device actually advertises beats
         * hardcoding one and supporting half the controllers.
         */
        val rightStick: Pair<Int, Int>? = when {
            absCodes.contains(ABS_RX) && absCodes.contains(ABS_RY) -> ABS_RX to ABS_RY
            absCodes.contains(ABS_Z) && absCodes.contains(ABS_RZ) -> ABS_Z to ABS_RZ
            else -> null
        }

        val looksLikeGamepad: Boolean =
            rightStick != null && keyCodes.any { it in 0x130..0x13F }
    }

    /**
     * Every input device the kernel is publishing, from /proc/bus/input/devices.
     *
     * Parsed rather than assumed: event node numbers move. The reference
     * implementation for this handheld ships three different hardcoded
     * fallbacks across three files — event3, event4 and event8 — which is what
     * happens when you trust the numbering.
     */
    fun devices(): List<Device> {
        val text = try {
            File("/proc/bus/input/devices").readText()
        } catch (e: Exception) {
            return emptyList()
        }

        val out = mutableListOf<Device>()
        for (block in text.trim().split("\n\n")) {
            var name = ""
            var node = ""
            var absBits = ""
            var keyBits = ""

            for (raw in block.lines()) {
                val line = raw.trim()
                when {
                    line.startsWith("N: Name=") ->
                        name = line.substringAfter('"').substringBeforeLast('"')
                    line.startsWith("H: Handlers=") ->
                        node = line.substringAfter('=').trim().split(" ")
                            .firstOrNull { it.startsWith("event") } ?: ""
                    line.startsWith("B: ABS=") -> absBits = line.substringAfter('=').trim()
                    line.startsWith("B: KEY=") -> keyBits = line.substringAfter('=').trim()
                }
            }
            if (node.isEmpty()) continue

            out.add(
                Device(
                    path = "/dev/input/$node",
                    name = name,
                    absCodes = decodeBitmap(absBits),
                    keyCodes = decodeBitmap(keyBits),
                )
            )
        }
        return out
    }

    fun findGamepad(): Device? = devices().firstOrNull { it.looksLikeGamepad }

    /**
     * The capability bitmaps in /proc are printed as space-separated 64-bit hex
     * words, **most significant first** — so the last word holds bits 0..63.
     * Reading them left to right is the easy way to decide a pad has no buttons.
     */
    fun decodeBitmap(hex: String): Set<Int> {
        if (hex.isBlank()) return emptySet()
        val words = hex.trim().split(Regex("\\s+"))
        val out = mutableSetOf<Int>()
        for ((index, word) in words.withIndex()) {
            val value = try {
                java.lang.Long.parseUnsignedLong(word, 16)
            } catch (e: NumberFormatException) {
                continue
            }
            val base = (words.size - 1 - index) * 64
            for (bit in 0 until 64) {
                if ((value ushr bit) and 1L == 1L) out.add(base + bit)
            }
        }
        return out
    }

    /** One event off the wire. */
    data class Event(val type: Int, val code: Int, val value: Int)

    /**
     * Reads whole events out of a buffer, ignoring a trailing partial one.
     *
     * A read can land mid-event; treating the tail as a complete record is how
     * you get a phantom axis slammed to an extreme.
     */
    fun parse(buffer: ByteArray, length: Int): List<Event> {
        val out = mutableListOf<Event>()
        val bb = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        while (bb.remaining() >= EVENT_SIZE) {
            bb.position(bb.position() + 16) // timeval
            val type = bb.short.toInt() and 0xFFFF
            val code = bb.short.toInt() and 0xFFFF
            val value = bb.int
            out.add(Event(type, code, value))
        }
        return out
    }

    fun open(path: String): FileInputStream = FileInputStream(path)
}

/**
 * The range an axis actually uses.
 *
 * This cannot be guessed. A pad reporting 0..255 sits entirely *inside* an
 * assumed signed-16-bit range, so every reading normalises to about zero and
 * the stick simply appears dead — and no amount of watching it will reveal the
 * mistake, because nothing ever falls outside the assumption.
 *
 * So the range is read from the device. `EVIOCGABS` is an ioctl and out of
 * reach from Java, but Android's own `InputDevice.getMotionRange` reports the
 * same numbers, works from any process, and needs no focus — see
 * [AxisRanges.fromInputDevice]. Widening only covers the opposite case: a pad
 * that reports values beyond the range it claims.
 */
class AxisRange(var minimum: Int = -32768, var maximum: Int = 32767) {

    /** True once a real range has been read off the device. */
    var known: Boolean = false
        private set

    fun observe(raw: Int) {
        if (raw < minimum) minimum = raw
        if (raw > maximum) maximum = raw
    }

    fun set(min: Int, max: Int) {
        if (max > min) {
            minimum = min
            maximum = max
            known = true
        }
    }

    fun normalise(raw: Int): Float = AimEngine.normalise(raw, minimum, maximum)

    override fun toString(): String =
        "$minimum..$maximum${if (known) "" else " (assumed)"}"
}
