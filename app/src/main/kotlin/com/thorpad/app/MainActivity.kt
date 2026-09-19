package com.thorpad.app

import android.app.Activity
import android.content.Context
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
    private lateinit var mode: Button
    private var focusToggle: Button? = null
    private var stickRoute: TextView? = null
    private lateinit var tapLength: Button
    private lateinit var duckToggle: Button
    private lateinit var holdJitter: Button
    private lateinit var sensitivity: Button
    private lateinit var cursorSize: Button

    private val ticker = Handler(Looper.getMainLooper())
    private lateinit var mascot: MascotView

    /** Where the tutorial has got to, or -1 when it is not running. */
    private var tutorialStep = -1

    private val prefs by lazy {
        getSharedPreferences("thorpad", Context.MODE_PRIVATE)
    }
    private val store by lazy { Store(this) }

    private val service: OverlayService? get() = OverlayService.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        // Only unprompted the first time. A mascot that explains the app again
        // every launch is a mascot you learn to dismiss without reading.
        if (!prefs.getBoolean("tutorialSeen", false)) startTutorial()
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

        mascot = MascotView(this).apply {
            onTapped = { advanceTutorial() }
        }
        root.addView(
            mascot,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            },
        )

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
        root.addView(wide("Show me how") { startTutorial() })
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

        // One state, one control. Four buttons for a two-state toggle was how
        // you ended up in edit mode without knowing it, wondering why nothing
        // reached the game.
        mode = wide("") { flipMode() }
        root.addView(mode)
        root.addView(
            note(
                "Editing makes the overlay draggable — which means it also " +
                    "catches the taps meant for the game, so nothing gets " +
                    "through until you go back to live."
            )
        )

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Markers") {
            OverlayService.send(this, OverlayService.ACTION_MARKERS)
            remark(
                if (service?.markers == false) {
                    "Circles hidden. The crosshair stays, and presses still " +
                        "flash so you can see them land."
                } else {
                    "Circles back on."
                }
            )
            refresh()
        })
        row.addView(button("Stop controls") {
            OverlayService.send(this, OverlayService.ACTION_STOP)
            refresh()
        })
        root.addView(row)
        root.addView(
            note(
                "Markers hides the control circles and leaves the crosshair, " +
                    "since the crosshair is the thing you are actually looking " +
                    "at. Buttons keep working either way."
            )
        )

        root.addView(section("crosshair"))
        sensitivity = wide("") {
            OverlayService.send(this, OverlayService.ACTION_SENSITIVITY)
            val speed = service?.settings?.maxSpeed ?: 2.2f
            remark(
                "Crosshair at ${"%.1f".format(speed)} screens a second. " +
                    "Remember it covers more ground when the game is zoomed."
            )
            refresh()
        }
        root.addView(sensitivity)
        cursorSize = wide("") {
            OverlayService.send(this, OverlayService.ACTION_CURSOR_SIZE)
            refresh()
        }
        root.addView(cursorSize)
        root.addView(
            note(
                "A button set to fire at the crosshair now follows it while " +
                    "held, so one finger can hold and aim at once — which is " +
                    "what a game wants when holding zooms and dragging looks " +
                    "around."
            )
        )

        root.addView(section("if a tap registers but nothing happens"))
        tapLength = wide("") {
            OverlayService.send(this, OverlayService.ACTION_TAP_LENGTH)
            val ms = service?.tapMs ?: 140
            remark(
                when {
                    ms <= 70 -> "70ms is about two frames at 30fps — quick, " +
                        "and the first thing to blame if a button ignores you."
                    ms <= 140 -> "140ms. A good default: long enough for most " +
                        "games to count it as a real press."
                    ms <= 240 -> "240ms. Try this if taps are landing but not " +
                        "registering."
                    else -> "400ms is a deliberate press. Slow, but hard for " +
                        "a game to miss."
                }
            )
            refresh()
        }
        root.addView(tapLength)
        root.addView(
            note(
                "A game reads touches once a frame, so a tap shorter than two " +
                    "frames can have its press and release land in the same " +
                    "one — the touch shows up, the button ignores it. If you " +
                    "can see the tap arrive and nothing happens, make this " +
                    "longer first."
            )
        )

        holdJitter = wide("") {
            OverlayService.send(this, OverlayService.ACTION_JITTER)
            val px = service?.holdJitter ?: 2f
            remark(
                if (px <= 0f) {
                    "Perfectly still. Some games stop acting on a finger that " +
                        "isn't moving, so if holding stops working, that's why."
                } else {
                    "${px.toInt()}px of drift while held — enough to count as " +
                        "movement, nowhere near enough to drag anything."
                }
            )
            refresh()
        }
        root.addView(holdJitter)
        root.addView(
            note(
                "A held finger that never moves emits a press and then " +
                    "nothing, and some games take that as a finger sitting " +
                    "idle and stop acting on it — so holding to keep shooting " +
                    "quietly stops. This drifts it a pixel or two, which is " +
                    "movement without dragging anything."
            )
        )

        duckToggle = wide("") {
            OverlayService.send(this, OverlayService.ACTION_DUCK)
            remark(
                if (service?.duck == true) {
                    "I'll get out of the way while tapping. If that fixes a " +
                        "dead button, the overlay was obscuring it."
                } else {
                    "Staying put while tapping."
                }
            )
            refresh()
        }
        root.addView(duckToggle)
        root.addView(
            note(
                "The other possibility: Android marks a touch as obscured when " +
                    "another app's window is above it, and a button can be set " +
                    "to refuse obscured touches. This shrinks the overlay to a " +
                    "pixel while the tap lands. If that fixes it, that was why."
            )
        )

        if (Build.VERSION.SDK_INT < 34) {
            root.addView(section("sticks on android ${Build.VERSION.SDK_INT}"))
            root.addView(
                note(
                    "Analog sticks are motion events, and before Android 14 " +
                        "nothing sees them without help. Buttons are unaffected " +
                        "— they arrive through a global key hook that costs the " +
                        "game nothing."
                )
            )

            stickRoute = TextView(this).apply {
                textSize = 12f
                setPadding(0, dp(4), 0, dp(8))
            }
            root.addView(stickRoute)

            root.addView(wide("Use Shizuku (recommended)") { askShizuku() })
            root.addView(
                note(
                    "Shizuku reads the stick from the kernel as shell, which " +
                        "needs no focus at all — the kernel has no idea what " +
                        "focus is. Costs the game nothing; costs you starting " +
                        "Shizuku again after each reboot."
                )
            )

            focusToggle = wide("") { flipFocus() }
            root.addView(focusToggle)
            root.addView(
                note(
                    "The fallback, and a poor one: a window holding focus can " +
                        "see the stick, but a game that loses focus usually " +
                        "mutes and pauses. Only worth it if Shizuku is not an " +
                        "option."
                )
            )
        }

        val adders = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        adders.addView(button("Button") { addControl() })
        adders.addView(button("Stick (drag)") { addStick(Kind.STICK) })
        adders.addView(button("Stick (crosshair)") { addStick(Kind.CURSOR) })
        root.addView(adders)
        root.addView(
            note(
                "A button taps one point. A drag stick pulls a finger around " +
                    "the screen. A crosshair stick injects nothing at all — it " +
                    "just moves a marker, and a button set to 'at crosshair' " +
                    "taps wherever it is. Which one a game understands is worth " +
                    "finding out rather than guessing."
            )
        )
        root.addView(
            note(
                "Binding happens on the overlay now: press Edit, tap a " +
                    "control, then press the gamepad button you want."
            )
        )

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

    /** Start it, or flip between editing and live. */
    private fun flipMode() {
        val running = service
        val wasEditing = running?.editing == true
        if (running == null || !running.showing) {
            OverlayService.send(this, OverlayService.ACTION_START)
            remark("Controls are up. Press a bound button and watch it flash.",
                Mood.PLEASED)
        } else {
            OverlayService.send(this, OverlayService.ACTION_TOGGLE)
            remark(
                if (wasEditing) {
                    "Live again — your buttons reach the game now."
                } else {
                    "Editing. Nothing I tap will reach the game until you're " +
                        "done, because the overlay has to catch your finger."
                },
                if (wasEditing) Mood.PLEASED else Mood.TALKING,
            )
        }
        mode.postDelayed({ refresh() }, 120)
    }

    private fun askShizuku() {
        when {
            !Sticks.shizukuRunning() -> {
                hint.text = "Shizuku is not running. Start it (wireless " +
                    "debugging is the no-root way), then come back.\n\n" +
                    "It reads /dev/input as shell, which needs no focus — so " +
                    "unlike the fallback it costs the game nothing."
            }
            !Sticks.shizukuReady() -> {
                Sticks.requestShizuku()
                hint.text = "Allow thorpad in the Shizuku prompt."
            }
            else -> {
                // Restarted so the reader actually starts: the route is chosen
                // when the controls come up, not per frame.
                OverlayService.send(this, OverlayService.ACTION_STOP)
                OverlayService.send(this, OverlayService.ACTION_START)
                hint.text = "Shizuku allowed. Sticks now read from /dev/input."
                remark(
                    "Sticks are coming off the kernel now — no focus taken, so " +
                        "your game keeps its sound.",
                    Mood.PLEASED,
                )
            }
        }
        refresh()
    }

    private fun flipFocus() {
        val running = service
        if (running == null) {
            status.text = "Start the controls first."
            return
        }
        running.focusAllowed = !running.focusAllowed
        remark(
            if (running.focusAllowed) {
                "Careful — holding focus is how I see a stick below Android " +
                    "14, but a game that loses focus usually mutes and pauses."
            } else {
                "Focus released. Buttons don't need it, so your game is left " +
                    "alone."
            },
            if (running.focusAllowed) Mood.TALKING else Mood.PLEASED,
        )
        refresh()
    }

    // ------------------------------------------------------------- mascot

    private fun startTutorial() {
        tutorialStep = 0
        showTutorialStep()
    }

    private fun advanceTutorial() {
        if (tutorialStep < 0) {
            mascot.hide()
            return
        }
        tutorialStep++
        if (tutorialStep >= Tutorial.steps.size) {
            tutorialStep = -1
            prefs.edit().putBoolean("tutorialSeen", true).apply()
            mascot.hide()
            return
        }
        showTutorialStep()
    }

    private fun showTutorialStep() {
        val step = Tutorial.steps[tutorialStep]
        val counter = "${tutorialStep + 1}/${Tutorial.steps.size}  "
        // Sticky: a tutorial step is finished by the reader, not by a timer.
        mascot.say(counter + step.text, step.mood, sticky = true)
    }

    /**
     * Has her comment on a change.
     *
     * Never while the tutorial is up — interrupting her own explanation to
     * remark on a setting would lose whichever the reader was part-way through.
     */
    private fun remark(message: String, mood: Mood = Mood.TALKING) {
        if (tutorialStep >= 0) return
        mascot.say(message, mood)
    }

    private fun refresh() {
        val canTap = TapService.isEnabled(this)
        val canDraw = OverlayService.canDraw(this)
        val showing = service?.showing == true
        val editing = service?.editing == true

        mode.text = when {
            !showing -> "START CONTROLS"
            editing -> "EDITING  —  tap to go live"
            else -> "● LIVE  —  tap to edit"
        }
        mode.setBackgroundColor(
            when {
                !showing -> Color.parseColor("#2A3038")
                editing -> Color.parseColor("#6A5A18")
                else -> Color.parseColor("#1F4A33")
            }
        )
        mode.setTextColor(
            when {
                !showing -> Color.parseColor("#C8D4DE")
                editing -> Color.parseColor("#FFD54A")
                else -> Color.parseColor("#9BE28B")
            }
        )

        val speed = service?.settings?.maxSpeed ?: 2.2f
        sensitivity.text = "Crosshair speed: ${"%.1f".format(speed)} screens/sec" +
            "  —  tap to change"
        val size = service?.cursorSize ?: 0.035f
        cursorSize.text = "Crosshair size: " + when {
            size <= 0.02f -> "small"
            size <= 0.04f -> "medium"
            size <= 0.07f -> "large"
            else -> "huge"
        } + "  —  tap to change"

        tapLength.text = "Tap length: ${service?.tapMs ?: 140}ms  —  tap to change"
        val jitter = service?.holdJitter ?: 2f
        holdJitter.text = if (jitter <= 0f) {
            "Held finger: perfectly still  —  tap to change"
        } else {
            "Held finger drifts ${jitter.toInt()}px  —  tap to change"
        }
        duckToggle.text = if (service?.duck == true) {
            "Overlay ducks while tapping: ON"
        } else {
            "Overlay ducks while tapping: off"
        }
        duckToggle.setBackgroundColor(
            if (service?.duck == true) Color.parseColor("#1F4A33")
            else Color.parseColor("#2A3038")
        )
        duckToggle.setTextColor(Color.parseColor("#C8D4DE"))

        val stealing = service?.needsFocus() == true
        val route = service?.source() ?: Sticks.best(service?.focusAllowed == true)
        stickRoute?.apply {
            text = "Stick route: ${route.label}"
            setTextColor(
                when (route) {
                    StickSource.MOTION_EVENTS, StickSource.SHIZUKU ->
                        Color.parseColor("#9BE28B")
                    StickSource.FOCUSED_OVERLAY -> Color.parseColor("#E0725A")
                    StickSource.NONE -> Color.parseColor("#C9A227")
                }
            )
        }
        focusToggle?.let { toggle ->
            toggle.text = if (service?.focusAllowed == true) {
                "STICKS ON  —  the game may mute and pause"
            } else {
                "STICKS OFF  —  buttons only, game untouched"
            }
            toggle.setBackgroundColor(
                if (service?.focusAllowed == true) Color.parseColor("#6A2A2A")
                else Color.parseColor("#2A3038")
            )
            toggle.setTextColor(
                if (service?.focusAllowed == true) Color.parseColor("#FFB4A6")
                else Color.parseColor("#C8D4DE")
            )
        }

        status.text = when {
            !canTap -> "Accessibility is off — thorpad cannot tap the screen."
            !canDraw -> "Draw over other apps is off — no controls, and no gamepad."
            !showing -> "Ready."
            editing -> "Editing. Tap a control, then press a gamepad button to bind it."
            stealing -> "Live, holding focus for the sticks — the game may mute."
            else -> "Live. Buttons tap the game; your fingers reach it too."
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
            append("Android ${Build.VERSION.SDK_INT} · ")
            append("accessibility ").append(if (canTap) "on" else "OFF")
            append(" · overlay ").append(if (canDraw) "allowed" else "BLOCKED")
            append(" · window ").append(
                when {
                    !showing -> "down"
                    editing -> "EDITING"
                    else -> "live"
                }
            )
            append(" · markers ").append(if (service?.markers != false) "on" else "off")
            append("\ntap length: ").append(service?.tapMs ?: 140).append("ms")
            append(" · ducking ").append(if (service?.duck == true) "on" else "off")
            append("\nhold jitter: ").append((service?.holdJitter ?: 2f).toInt()).append("px")
            append(" · crosshair ").append("%.1f".format(speed)).append(" screens/s")
            append("\nstick route: ").append(route.label)
            append("\nholding focus: ").append(if (stealing) "YES — game may mute" else "no")
            if (route == StickSource.SHIZUKU) {
                append("\nreader: ").append(service?.shellReport ?: "—")
            }
            append("\ngamepad keys seen: ${service?.keysSeen ?: 0}")
            service?.lastKey?.takeIf { it != 0 }?.let {
                append(" (last ${Buttons.nameOf(it)})")
            }
            if (service?.layout?.usesSticks == true) {
                append("\nstick events: ${service?.motionSeen ?: 0}")
                append(" · ${service?.lastStick ?: "—"}")
                append("\nstick source: ")
            append(
                if (Build.VERSION.SDK_INT >= 34) {
                    tapper?.stickSource ?: "—"
                } else {
                    "focused overlay (Android ${Build.VERSION.SDK_INT})"
                }
            )
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
                text = when {
                    control.isStick && control.bound ->
                        "${control.label}  →  ${control.stick!!.name.lowercase()} stick"
                    control.isStick -> "${control.label}  →  no stick"
                    else -> "${control.label}  →  ${Buttons.nameOf(control.keyCode)}" +
                        if (control.press == Press.HOLD) {
                            "  (touch lasts as long as the button)"
                        } else {
                            "  (quick tap)"
                        }
                }
                setTextColor(
                    if (control.bound) Color.parseColor("#D7DEE5")
                    else Color.parseColor("#E0725A")
                )
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })

            if (control.isStick) {
                box.addView(button("L") {
                    update(layoutNow().bindStick(control.id, Stick.LEFT))
                })
                box.addView(button("R") {
                    update(layoutNow().bindStick(control.id, Stick.RIGHT))
                })
            } else {
                box.addView(button("Bind") { learn(control.id) })
                box.addView(button(if (control.press == Press.HOLD) "→Tap" else "→Hold") {
                    update(
                        layoutNow().replace(
                            control.copy(
                                press = if (control.press == Press.HOLD) Press.TAP else Press.HOLD
                            )
                        )
                    )
                })
                box.addView(button(if (control.atCursor) "✛ on" else "✛ off") {
                    update(
                        layoutNow().replace(control.copy(atCursor = !control.atCursor))
                    )
                })
            }
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

    /**
     * A stick control, covering the whole screen by default.
     *
     * Full size because travel is the whole problem: a drag ends at the edge of
     * its region and has to lift and start again, so a smaller region only
     * means more of those hitches.
     */
    private fun addStick(kind: Kind) {
        val layout = layoutNow()
        val taken = layout.sticks().mapNotNull { it.stick }.toSet()
        val free = Stick.entries.firstOrNull { it !in taken }
        update(
            layout.add(
                Control(
                    id = Layout.nextId(layout),
                    label = if (kind == Kind.CURSOR) "✛" else "aim",
                    keyCode = 0,
                    x = 0.5f,
                    y = 0.5f,
                    kind = kind,
                    stick = free ?: Stick.RIGHT,
                )
            )
        )
        if (Build.VERSION.SDK_INT < 34) {
            status.text = "Added. On Android ${Build.VERSION.SDK_INT} a stick " +
                "needs a route — Shizuku is the one that costs the game nothing."
        }
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
    /**
     * Arms a control so the next button pressed binds to it.
     *
     * Also reachable by tapping the control on the overlay in edit mode, which
     * is the better way round: the controls are placed over the game, so that
     * is where you are looking when you decide what a button should do.
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
