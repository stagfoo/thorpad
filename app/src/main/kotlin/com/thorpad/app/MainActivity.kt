package com.thorpad.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Setup, and nothing else.
 *
 * The controls are placed on the overlay, over the game, because that is the
 * only place the positions mean anything. This screen exists to turn the two
 * permissions on, add and remove controls, and say plainly which stage of the
 * chain is working — the thing every previous attempt at this was missing.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private lateinit var hint: TextView

    private val ticker = Handler(Looper.getMainLooper())
    private val store by lazy { Store(this) }

    private val service: OverlayService? get() = OverlayService.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        service?.onLearned = { id, keyCode -> onLearned(id, keyCode) }
        service?.onChanged = { runOnUiThread { refresh() } }
        refresh()
        poll()
    }

    override fun onPause() {
        ticker.removeCallbacksAndMessages(null)
        super.onPause()
    }

    /**
     * Keeps the status honest while the user is in Settings turning things on.
     *
     * There is no callback for "accessibility was enabled" or "the overlay
     * permission was granted", and coming back to a screen that still says
     * "off" is how people conclude the app is broken.
     */
    private fun poll() {
        ticker.removeCallbacksAndMessages(null)
        ticker.postDelayed({ refresh(); poll() }, 700)
    }

    // ------------------------------------------------------------------- ui

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1013"))
            setPadding(dp(18), dp(16), dp(18), dp(28))
        }

        root.addView(heading("thorpad"))
        root.addView(
            note(
                "On-screen controls, over any game, driven by the gamepad. " +
                    "Put a control where you would tap, bind a button to it, " +
                    "and that button taps there."
            )
        )

        status = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(14), 0, dp(10))
        }
        root.addView(status)

        root.addView(section("1 — permissions"))
        root.addView(wide("Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(
            note(
                "Turn thorpad on there. This is what lets it tap the screen; " +
                    "it is the only way an app can do that without root."
            )
        )
        root.addView(wide("Draw over other apps") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        })
        root.addView(
            note(
                "This draws the controls over the game. It never takes focus, " +
                    "so back, volume and your fingers all keep working exactly " +
                    "as before."
            )
        )

        root.addView(section("2 — controls"))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Show") { OverlayService.send(this, OverlayService.ACTION_START) })
        row.addView(button("Edit") { OverlayService.send(this, OverlayService.ACTION_EDIT) })
        row.addView(button("Play") { OverlayService.send(this, OverlayService.ACTION_PLAY) })
        row.addView(button("Hide") { OverlayService.send(this, OverlayService.ACTION_STOP) })
        root.addView(row)
        root.addView(
            note(
                "Edit makes the overlay draggable. Play makes it invisible to " +
                    "touch so the game gets your fingers, while still catching " +
                    "the gamepad."
            )
        )

        root.addView(wide("Add a control") { addControl() })

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(controls)

        hint = TextView(this).apply {
            setTextColor(Color.parseColor("#8FB8D4"))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(hint)

        return ScrollView(this).apply { addView(root) }
    }

    private fun refresh() {
        val canTap = TapService.isEnabled(this)
        val canDraw = OverlayService.canDraw(this)
        val showing = service?.showing == true

        status.text = when {
            !canTap -> "Accessibility is off — thorpad cannot tap the screen."
            !canDraw -> "Draw over other apps is off — no controls, and no gamepad."
            !showing -> "Ready. Press Show."
            service?.editing == true -> "Editing. Drag the controls where you want them."
            else -> "Playing. Buttons tap; your fingers go to the game."
        }
        status.setTextColor(
            when {
                !canTap || !canDraw -> Color.parseColor("#E0725A")
                showing -> Color.parseColor("#9BE28B")
                else -> Color.parseColor("#C9A227")
            }
        )

        val tapper = TapService.instance
        hint.text = buildString {
            append("accessibility ").append(if (canTap) "on" else "OFF")
            append(" · overlay ").append(if (canDraw) "allowed" else "BLOCKED")
            append(" · window ").append(if (showing) "up" else "down")
            append("\ngamepad keys seen: ${service?.keysSeen ?: 0}")
            service?.lastKey?.takeIf { it != 0 }?.let {
                append(" (last ${Buttons.nameOf(it)})")
            }
            append("\ntaps sent: ${tapper?.sent ?: 0}")
            append(", completed: ${tapper?.completed ?: 0}")
            (tapper?.refused ?: 0).takeIf { it > 0 }?.let { append(", refused: $it") }
            tapper?.lastError?.let { append("\n").append(it) }
        }

        renderControls()
    }

    private fun renderControls() {
        controls.removeAllViews()
        val layout = service?.layout ?: store.load()

        if (layout.controls.isEmpty()) {
            controls.addView(note("No controls yet. Add one, then bind a button."))
            return
        }

        for (control in layout.controls) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }

            box.addView(TextView(this).apply {
                text = "${control.label}  →  ${Buttons.nameOf(control.keyCode)}" +
                    if (control.press == Press.HOLD) "  (hold)" else ""
                setTextColor(
                    if (control.bound) Color.parseColor("#D7DEE5")
                    else Color.parseColor("#E0725A")
                )
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })

            box.addView(button("Bind") { learn(control.id) })
            box.addView(button(if (control.press == Press.HOLD) "Tap" else "Hold") {
                update(
                    layoutNow().replace(
                        control.copy(
                            press = if (control.press == Press.HOLD) Press.TAP else Press.HOLD
                        )
                    )
                )
            })
            box.addView(button("×") { update(layoutNow().remove(control.id)) })

            controls.addView(box)
        }
    }

    // --------------------------------------------------------------- edits

    private fun layoutNow(): Layout = service?.layout ?: store.load()

    private fun update(layout: Layout) {
        val running = service
        if (running != null) {
            running.update(layout)
        } else {
            store.save(layout)
        }
        refresh()
    }

    private fun addControl() {
        val layout = layoutNow()
        val id = Layout.nextId(layout)
        val spot = nextSpot(layout.controls.size)
        update(
            layout.add(
                Control(
                    id = id,
                    label = "${layout.controls.size + 1}",
                    keyCode = 0,
                    x = spot.first,
                    y = spot.second,
                )
            )
        )
    }

    /** Somewhere new for each added control, so they do not stack on one point. */
    private fun nextSpot(existing: Int): Pair<Float, Float> {
        val column = existing % 4
        val row = existing / 4
        return Pair(0.2f + column * 0.2f, 0.3f + row * 0.15f)
    }

    /**
     * Waits for a button press and binds it.
     *
     * The overlay has to be up for this: it is the only window that sees the
     * gamepad, so with nothing on screen no button would ever arrive and the
     * prompt would hang for ever.
     */
    private fun learn(id: String) {
        if (!TapService.isEnabled(this)) {
            status.text = "Turn on Accessibility first — that is what sees " +
                "the gamepad."
            return
        }
        val running = service
        if (running == null) {
            OverlayService.send(this, OverlayService.ACTION_START)
            status.text = "Starting the controls — press Bind again."
            return
        }
        running.learningFor = id
        status.text = "Press the button you want for this control…"
        status.setTextColor(Color.parseColor("#6FC9FF"))
    }

    private fun onLearned(id: String, keyCode: Int) {
        runOnUiThread {
            update(layoutNow().bind(id, keyCode))
            status.text = "Bound to ${Buttons.nameOf(keyCode)}."
        }
    }

    // --------------------------------------------------------------- views

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 22f
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(Color.parseColor("#5E6A75"))
        textSize = 11f
        letterSpacing = 0.14f
        setPadding(0, dp(22), 0, dp(6))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#7C8590"))
        textSize = 12f
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 11f
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun wide(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 12f
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onClick() }
    }
}
