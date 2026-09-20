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
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
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
    private lateinit var duckToggle: Button
    private lateinit var tapLength: View
    private lateinit var holdJitter: View
    private lateinit var sensitivity: View
    private lateinit var cursorSize: View
    private lateinit var aimRegion: View
    private lateinit var aimHold: View

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
        CrashLog.install(this)
        setContentView(buildUi())

        // Surfaced rather than filed away: a crash nobody sees is a crash that
        // gets described from memory next time.
        CrashLog.lastCrash(this)?.let {
            hint.text = "thorpad crashed last time. Tap 'Last crash' to see it."
            status.text = "Recovered from a crash."
            status.setTextColor(Color.parseColor("#E0725A"))
        }
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

        root.addView(section("aim stick"))
        aimRegion = slider(
            "Aim region",
            0.25f..1f,
            layoutNow().sticks().firstOrNull { it.isDragStick }?.width ?: 0.6f,
            { "%.0f%% of the screen".format(it * 100) },
        ) { value -> resizeAimRegion(value) }
        root.addView(aimRegion)
        aimHold = slider(
            "Lift after centring",
            0f..3200f,
            (service?.settings?.holdMs ?: 220).let { if (it < 0) 3200f else it.toFloat() },
            { if (it >= 3100f) "never — use a release button" else "${it.toInt()}ms" },
        ) { value ->
            val ms = if (value >= 3100f) -1f else value
            OverlayService.send(this, OverlayService.ACTION_AIM_HOLD, ms)
            remark(
                if (ms < 0) {
                    "The aim will stay down until a release button ends it. " +
                        "That's what a gun that fires on release needs."
                } else {
                    "Lifting ${value.toInt()}ms after you centre the stick. " +
                        "Careful if the weapon fires when your finger comes up."
                }
            )
            refresh()
        }
        root.addView(aimHold)
        root.addView(
            note(
                "Centring the stick normally lifts the finger — which fires a " +
                    "weapon that shoots on release. Set this to never and bind " +
                    "a button to ↥ instead, so letting go is a decision."
            )
        )
        root.addView(
            note(
                "How far the finger may sit from where it pressed — which is " +
                    "the fastest the game will turn. Other mappers pin this to " +
                    "a small circle; here it goes to the whole screen."
            )
        )

        root.addView(section("crosshair"))
        sensitivity = slider(
            "Crosshair speed",
            OverlayService.SENSITIVITY_RANGE,
            service?.settings?.maxSpeed ?: 2.2f,
            { "%.1f screens/sec".format(it) },
        ) { value ->
            OverlayService.send(this, OverlayService.ACTION_SENSITIVITY, value)
            remark(
                "%.1f screens a second. Remember it covers more ground when " .format(value) +
                    "the game is zoomed in."
            )
            refresh()
        }
        root.addView(sensitivity)
        cursorSize = slider(
            "Crosshair size",
            OverlayService.CURSOR_SIZE_RANGE,
            service?.cursorSize ?: 0.035f,
            { "%.0f%% of the screen".format(it * 100) },
        ) { value ->
            OverlayService.send(this, OverlayService.ACTION_CURSOR_SIZE, value)
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
        tapLength = slider(
            "Tap length",
            OverlayService.TAP_MS_RANGE,
            (service?.tapMs ?: 140L).toFloat(),
            { "${it.toInt()}ms" },
        ) { value ->
            OverlayService.send(this, OverlayService.ACTION_TAP_MS, value)
            remark(
                when {
                    value <= 70f -> "That's about two frames at 30fps. Quick — " +
                        "and the first thing to blame if a button ignores you."
                    value <= 180f -> "${value.toInt()}ms. Long enough for most " +
                        "games to count it as a real press."
                    value <= 320f -> "${value.toInt()}ms — worth trying if taps " +
                        "land but don't register."
                    else -> "${value.toInt()}ms is a deliberate press. Slow, " +
                        "but hard for a game to miss."
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

        holdJitter = slider(
            "Held finger drift",
            OverlayService.JITTER_RANGE,
            service?.holdJitter ?: 2f,
            { if (it < 0.5f) "perfectly still" else "${it.toInt()}px" },
        ) { value ->
            OverlayService.send(this, OverlayService.ACTION_JITTER, value)
            remark(
                if (value < 0.5f) {
                    "Perfectly still. Some games stop acting on a finger that " +
                        "isn't moving — if holding stops working, that's why."
                } else {
                    "${value.toInt()}px of drift while held. Movement, but " +
                        "nowhere near enough to drag anything."
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
        adders.addView(button("Aim stick") { addStick(Kind.STICK) })
        adders.addView(button("Crosshair") { addStick(Kind.CURSOR) })
        adders.addView(button("Slot strip") { addStrip() })
        root.addView(adders)
        root.addView(
            note(
                "An aim stick presses and holds a finger away from where it " +
                    "pressed — which is how a game like this turns, and keeps " +
                    "turning while the stick is held. Make its region bigger " +
                    "and it turns faster; that is the small circle every other " +
                    "mapper is stuck with.\n\nA crosshair moves a marker and " +
                    "injects nothing, for a button set to ✛ on to touch."
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

        mascot = MascotView(this).apply {
            onTapped = { advanceTutorial() }
        }

        // Over the page rather than in it: she has to stay put while the
        // settings she is talking about scroll past behind her, and a mascot
        // that scrolls away mid-sentence is a mascot nobody finishes reading.
        return FrameLayout(this).apply {
            addView(
                ScrollView(this@MainActivity).apply {
                    addView(root)
                    // Room at the bottom so she never sits on the last control.
                    clipToPadding = false
                    setPadding(0, 0, 0, dp(140))
                },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
            )
            addView(
                mascot,
                FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    marginStart = dp(12)
                    marginEnd = dp(8)
                    bottomMargin = dp(4)
                },
            )
        }
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
            append("\nreader: ").append(service?.shellReport ?: "—")
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
                        "${control.label}  →  ${control.stick!!.name.lowercase()} stick" +
                            if (control.isDragStick) "  (aim)" else "  (crosshair)"
                    control.isStick -> "${control.label}  →  no stick"
                    control.isStrip -> {
                        val fwd = if (control.keyCode != 0) {
                            Buttons.nameOf(control.keyCode)
                        } else "—"
                        val back = if (control.keyCodePrev != 0) {
                            Buttons.nameOf(control.keyCodePrev)
                        } else "—"
                        "${control.label}  →  $back / $fwd  (${control.slots} slots)"
                    }
                    control.releasesAim ->
                        "${control.label}  →  ${Buttons.nameOf(control.keyCode)}" +
                            "  (ends the aim)"
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

            if (control.isStrip) {
                box.addView(button("→") { learn(control.id) })
                box.addView(button("←") { learnPrev(control.id) })
                box.addView(button("${control.slots}") {
                    val next = if (control.slots >= 8) 2 else control.slots + 1
                    update(layoutNow().replace(control.copy(slots = next)))
                })
                box.addView(button(if (control.vertical) "↕" else "↔") {
                    // The length runs along whichever way it points, so the two
                    // swap when it turns — otherwise turning a wide strip
                    // vertical makes a stubby one.
                    update(
                        layoutNow().replace(
                            control.copy(
                                vertical = !control.vertical,
                                width = control.height,
                                height = control.width,
                            )
                        )
                    )
                })
            } else if (control.isStick) {
                box.addView(button(if (control.isDragStick) "✛" else "aim") {
                    update(
                        layoutNow().replace(
                            control.copy(
                                kind = if (control.isDragStick) Kind.CURSOR else Kind.STICK
                            )
                        )
                    )
                })
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
                box.addView(button(if (control.releasesAim) "↥ on" else "↥ off") {
                    update(
                        layoutNow().replace(
                            control.copy(releasesAim = !control.releasesAim)
                        )
                    )
                    remark(
                        if (!control.releasesAim) {
                            "That button now ends an aim instead of touching. " +
                                "Bind it to whatever drops you back into cover."
                        } else {
                            "Back to a normal button."
                        }
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
    /** The aim region is the turn rate, so it gets a slider of its own. */
    private fun resizeAimRegion(size: Float) {
        var layout = layoutNow()
        val sticks = layout.controls.filter { it.isDragStick }
        if (sticks.isEmpty()) {
            status.text = "Add an aim stick first."
            return
        }
        for (control in sticks) {
            layout = layout.replace(control.copy(width = size, height = size))
        }
        update(layout)
        remark(
            "Aim region is %.0f%% of the screen. Bigger means the game turns " .format(size * 100) +
                "faster at full tilt."
        )
    }

    /**
     * A row of slots for a bar the game wants poked directly.
     *
     * Laid across the bottom by default, which is where that bar is in every
     * game that has one.
     */
    private fun addStrip() {
        val layout = layoutNow()
        update(
            layout.add(
                Control(
                    id = Layout.nextId(layout),
                    label = "slots",
                    keyCode = 0,
                    x = 0.5f,
                    y = 0.9f,
                    kind = Kind.STRIP,
                    slots = 5,
                    // Narrow to start with. A bar of portraits is clustered in
                    // the middle of a wide screen, not spread across it, and a
                    // strip that starts too wide taps empty air either side.
                    width = 0.35f,
                    height = 0.12f,
                )
            )
        )
        remark(
            "Drag it over the bar, then pull its blue ends until the slots sit " +
                "on the real ones — they're usually bunched in the middle, not " +
                "spread across the screen.",
            Mood.PLEASED,
        )
    }

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
                    // A big region by default, because a small one is the
                    // limitation this exists to lift.
                    width = if (kind == Kind.STICK) 0.7f else 1f,
                    height = if (kind == Kind.STICK) 0.7f else 1f,
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
    /** Arms a strip's backwards direction for the next button pressed. */
    private fun learnPrev(id: String) {
        if (!TapService.isEnabled(this)) {
            status.text = "Turn on Accessibility first — that is what sees " +
                "the gamepad."
            return
        }
        val running = service ?: run {
            OverlayService.send(this, OverlayService.ACTION_START)
            status.text = "Starting the controls — press ← again."
            return
        }
        running.learningFor = id
        running.learningPrev = true
        status.text = "Press the button that should step backwards…"
        status.setTextColor(Color.parseColor("#6FC9FF"))
    }

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
        running.learningPrev = false
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

    /**
     * A labelled slider with its value shown above it.
     *
     * SeekBar is integers only, so the range is stepped into a thousand and
     * converted either way — every one of these is a value found by feel, and
     * one that could only be set in whole units would not be a setting.
     */
    private fun slider(
        label: String,
        range: ClosedFloatingPointRange<Float>,
        value: Float,
        format: (Float) -> String,
        onChange: (Float) -> Unit,
    ): View {
        val steps = 1000
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(2))
        }

        val readout = TextView(this).apply {
            setTextColor(Color.parseColor("#D7DEE5"))
            textSize = 13f
            text = "$label   ${format(value)}"
        }
        box.addView(readout)

        box.addView(SeekBar(this).apply {
            max = steps
            val span = range.endInclusive - range.start
            progress = (((value - range.start) / span) * steps)
                .coerceIn(0f, steps.toFloat()).roundToInt()
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                    readout.text = "$label   ${format(range.start + span * p / steps)}"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                // Only on release: sending on every pixel of travel would be a
                // few hundred service calls a second for a value nobody has
                // settled on yet.
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    onChange(range.start + span * progress / steps)
                }
            })
        })

        return box
    }

    private fun wide(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 12f
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onClick() }
    }
}
