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
    /** Fraction of the screen, 0..1. */
    val x: Float,
    val y: Float,
    val press: Press = Press.TAP,
) {
    fun movedTo(nx: Float, ny: Float) = copy(
        x = nx.coerceIn(0f, 1f),
        y = ny.coerceIn(0f, 1f),
    )

    val bound: Boolean get() = keyCode != 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("keyCode", keyCode)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("press", press.name)
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
        controls.firstOrNull { it.keyCode == keyCode && it.bound }

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
    fun bind(id: String, keyCode: Int): Layout {
        if (this[id] == null) return this
        return Layout(
            controls.map {
                when {
                    it.id == id -> it.copy(keyCode = keyCode)
                    it.keyCode == keyCode -> it.copy(keyCode = 0)
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
