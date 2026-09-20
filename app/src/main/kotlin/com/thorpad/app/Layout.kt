package com.thorpad.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * What is mapped to what, and where.
 *
 * Positions are fractions of the screen rather than pixels, so a layout set up
 * once survives a rotation, a different screen and a different device. Pixels
 * are worked out at the last moment against whatever the overlay turns out to
 * be.
 *
 * No Android types in here, so the rules — one button cannot drive two
 * controls, a control has to be somewhere on screen, a layout has to survive
 * being saved — can be checked without a device.
 */

enum class Kind {
    /** A button that taps or holds one point. */
    BUTTON,

    /**
     * A stick that moves a crosshair, injecting nothing on its own.
     *
     * There was a second stick kind that dragged a finger around instead. It
     * worked, but it carried the whole recentring problem — a drag ends at the
     * edge of its region and has to lift and start again — and the crosshair
     * has none of that. Tested side by side on the device the crosshair won,
     * so the drag is gone rather than kept as a worse option to pick by
     * mistake.
     */
    CURSOR,

    /**
     * A stick held as a finger offset from where it pressed.
     *
     * How the mappers that work do it: the game turns because the finger is
     * away from where it went down, and keeps turning while it stays there. The
     * region's size is the top turn rate, which is the whole reason to want one
     * bigger than the small fixed circle every other mapper gives you.
     */
    STICK,
}

/** Which stick drives a [Kind.STICK] control. */
enum class Stick { LEFT, RIGHT }

enum class Press {
    /** Down and up, one quick touch. */
    TAP,

    /** Held for exactly as long as the button is held. */
    HOLD,
}

data class Control(
    val id: String,
    val label: String,
    /** Android keycode of the button that fires it, or 0 if unbound. */
    val keyCode: Int,
    /** Fraction of the screen, 0..1. For a stick, the centre of its region. */
    val x: Float,
    val y: Float,
    val press: Press = Press.TAP,
    val kind: Kind = Kind.BUTTON,
    val stick: Stick? = null,
    /**
     * For a button: fire at the crosshair rather than at its own spot.
     *
     * This is what makes a cursor useful — the stick puts the crosshair
     * somewhere, and this button taps there.
     */
    val atCursor: Boolean = false,
    /**
     * For a stick, how much of the screen the finger may drag across, as
     * fractions of it.
     *
     * The whole screen by default, because travel is the whole problem: a
     * stroke ends at the edge of its region and has to start again, so anything
     * smaller is throwing away swing for nothing.
     */
    val width: Float = 1f,
    val height: Float = 1f,
) {
    fun movedTo(nx: Float, ny: Float) = copy(
        x = nx.coerceIn(0f, 1f),
        y = ny.coerceIn(0f, 1f),
    )

    val isStick: Boolean get() = kind == Kind.CURSOR || kind == Kind.STICK

    val isCursor: Boolean get() = kind == Kind.CURSOR

    val isDragStick: Boolean get() = kind == Kind.STICK

    val isButton: Boolean get() = kind == Kind.BUTTON

    /** A button needs a key; a stick needs a stick. */
    val bound: Boolean
        get() = if (isStick) stick != null else keyCode != 0

    /** The region this stick may drag in, clamped to the screen. */
    fun region(): AimSettings.Region {
        val halfW = (width / 2f).coerceIn(0.05f, 0.5f)
        val halfH = (height / 2f).coerceIn(0.05f, 0.5f)
        var left = x - halfW
        var right = x + halfW
        var top = y - halfH
        var bottom = y + halfH
        // Shifted rather than squashed when it runs off an edge: a region that
        // silently shrank would cost travel, which is the one thing it exists
        // to provide.
        if (left < 0f) { right -= left; left = 0f }
        if (right > 1f) { left -= right - 1f; right = 1f }
        if (top < 0f) { bottom -= top; top = 0f }
        if (bottom > 1f) { top -= bottom - 1f; bottom = 1f }
        return AimSettings.Region(
            left.coerceIn(0f, 1f), top.coerceIn(0f, 1f),
            right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f),
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("keyCode", keyCode)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("press", press.name)
        put("kind", kind.name)
        stick?.let { put("stick", it.name) }
        put("atCursor", atCursor)
        put("width", width.toDouble())
        put("height", height.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject): Control? {
            val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
            return Control(
                id = id,
                label = json.optString("label", id),
                keyCode = json.optInt("keyCode", 0),
                x = json.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                y = json.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                press = runCatching {
                    Press.valueOf(json.optString("press", "TAP"))
                }.getOrDefault(Press.TAP),
                kind = runCatching {
                    Kind.valueOf(json.optString("kind", "BUTTON"))
                }.getOrDefault(Kind.BUTTON),
                stick = runCatching {
                    json.optString("stick").takeIf { it.isNotEmpty() }
                        ?.let { Stick.valueOf(it) }
                }.getOrNull(),
                atCursor = json.optBoolean("atCursor", false),
                width = json.optDouble("width", 1.0).toFloat().coerceIn(0.1f, 1f),
                height = json.optDouble("height", 1.0).toFloat().coerceIn(0.1f, 1f),
            )
        }
    }
}

data class Layout(val controls: List<Control> = emptyList()) {

    operator fun get(id: String): Control? = controls.firstOrNull { it.id == id }

    /**
     * The control a button fires, if any.
     *
     * First match wins, but [bind] makes duplicates impossible in the first
     * place — two controls on one button would fire both and there would be no
     * way to tell from the screen which one you meant.
     */
    fun forKey(keyCode: Int): Control? =
        controls.firstOrNull { it.isButton && it.keyCode == keyCode && it.bound }

    /** Every stick or cursor control that is actually bound to a stick. */
    fun sticks(): List<Control> = controls.filter { it.isStick && it.bound }

    /** The crosshair a button firing "at cursor" should aim at. */
    fun cursor(): Control? = controls.firstOrNull { it.isCursor && it.bound }

    val usesSticks: Boolean get() = controls.any { it.isStick }

    fun add(control: Control): Layout = Layout(controls + control)

    fun remove(id: String): Layout = Layout(controls.filterNot { it.id == id })

    fun replace(control: Control): Layout =
        Layout(controls.map { if (it.id == control.id) control else it })

    /**
     * Puts [keyCode] on [id], taking it off whatever had it.
     *
     * Stealing rather than refusing: you are holding the button you want, and
     * an error message about a conflict you cannot see is worse than moving it.
     * The control it came from is left unbound and says so on screen.
     */
    /** Puts [stick] on [id], taking it off whatever had it, for the same reason. */
    fun bindStick(id: String, stick: Stick): Layout {
        if (this[id] == null) return this
        return Layout(
            controls.map {
                when {
                    // Keeps whichever stick kind it already was: changing a
                    // crosshair into a drag because its stick was reassigned
                    // would be a surprise.
                    // Keeps whichever stick kind it already was; reassigning
                    // which thumbstick drives it is not a request to change
                    // what it does.
                    it.id == id -> it.copy(
                        stick = stick,
                        kind = if (it.isStick) it.kind else Kind.STICK,
                    )
                    it.isStick && it.stick == stick -> it.copy(stick = null)
                    else -> it
                }
            }
        )
    }

    fun bind(id: String, keyCode: Int): Layout {
        if (this[id] == null) return this
        return Layout(
            controls.map {
                when {
                    it.id == id -> it.copy(keyCode = keyCode)
                    it.isButton && it.keyCode == keyCode -> it.copy(keyCode = 0)
                    else -> it
                }
            }
        )
    }

    fun toJson(): String = JSONArray().apply {
        for (control in controls) put(control.toJson())
    }.toString()

    companion object {
        fun fromJson(text: String?): Layout {
            if (text.isNullOrBlank()) return Layout()
            return try {
                val array = JSONArray(text)
                val out = mutableListOf<Control>()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    Control.fromJson(item)?.let(out::add)
                }
                Layout(out)
            } catch (e: Exception) {
                // A layout that will not parse is a layout you no longer have.
                // An empty one you can rebuild beats a crash on launch.
                Layout()
            }
        }

        fun nextId(existing: Layout): String =
            "c${System.currentTimeMillis().toString().takeLast(6)}" +
                existing.controls.size
    }
}
