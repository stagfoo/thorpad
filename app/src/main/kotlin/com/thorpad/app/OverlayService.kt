package com.thorpad.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager

/**
 * Owns the window, and is the only thing that can see the gamepad.
 *
 * Android delivers key events to the *focused* window and nothing else, so a
 * background app cannot see a controller at all. The overlay is that window:
 * focusable, so the buttons arrive, and — while playing — untouchable, so every
 * finger still reaches the game underneath.
 *
 * A foreground service because the window has to outlive the app being
 * backgrounded, which is the entire time it is any use.
 */
class OverlayService : Service() {

    companion object {
        @Volatile var instance: OverlayService? = null

        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_EDIT = "edit"
        const val ACTION_PLAY = "play"

        /** Flips between editing and playing, which is all the two modes are. */
        const val ACTION_TOGGLE = "toggle"

        /** Show or hide the control markers, without touching the crosshair. */
        const val ACTION_MARKERS = "markers"

        /** Sets how long a tap presses for, in ms, via [EXTRA_VALUE]. */
        const val ACTION_TAP_MS = "tap-ms"

        /** Cycles how far a held finger wanders. */
        const val ACTION_JITTER = "jitter"

        /** Cycles how fast the crosshair moves. */
        const val ACTION_SENSITIVITY = "sensitivity"

        /** Cycles how big the crosshair is drawn. */
        const val ACTION_CURSOR_SIZE = "cursor-size"

        /** Get the overlay out of the way while a tap lands. */
        const val ACTION_DUCK = "duck"

        /** Sets how long after centring an aim stick lifts; negative is never. */
        const val ACTION_AIM_HOLD = "aim-hold"

        /** Carries a slider's value alongside its action. */
        const val EXTRA_VALUE = "value"

        // Continuous ranges rather than a few steps. Every one of these is
        // found by feel against a particular game, and a step that lands either
        // side of the number you wanted is worse than no step at all.
        val TAP_MS_RANGE = 40f..600f
        val JITTER_RANGE = 0f..14f
        val SENSITIVITY_RANGE = 0.4f..6f
        val CURSOR_SIZE_RANGE = 0.012f..0.11f

        private const val CHANNEL = "thorpad"
        private const val NOTIFICATION = 1

        fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun send(context: Context, action: String, value: Float? = null) {
            val intent = Intent(context, OverlayService::class.java).setAction(action)
            if (value != null) intent.putExtra(EXTRA_VALUE, value)
            if (action == ACTION_START) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var store: Store
    private var manager: WindowManager? = null
    private var view: OverlayView? = null

    /** Which control each held button is holding, so key-up releases the right one. */
    private val holding = mutableMapOf<Int, String>()

    /**
     * Held fingers that were put down at the crosshair, and so must follow it.
     *
     * The thing that makes a crosshair worth having. A game where holding zooms
     * and dragging aims needs one finger doing both — a hold that read the
     * crosshair once at press time would zoom and then refuse to look around,
     * which is most of the way to useless.
     */
    private val followingCursor = mutableSetOf<String>()

    @Volatile var editing = false
        private set

    /**
     * Whether the control markers are painted.
     *
     * Not the same as the overlay being up, and deliberately not the same as
     * the crosshair: the markers are a reference you stop needing once you know
     * the layout, while the crosshair is the aim itself.
     */
    @Volatile var markers = true
        private set

    /**
     * How long a tap presses for.
     *
     * Adjustable because the right answer depends on the game's frame rate and
     * how its engine counts a press, neither of which can be read from outside
     * it. A touch that registers visibly while the button under it ignores you
     * is this number being too small.
     */
    /**
     * Whether to shrink the overlay to a pixel while a tap lands.
     *
     * A test, and possibly a fix. Android marks a touch as obscured when
     * another app's window sits above the point it landed on, and a view can be
     * set to ignore obscured touches entirely — which would look exactly like
     * this: the touch plainly arrives, and the button under it refuses it.
     *
     * Whether a not-touchable overlay counts as obscuring is not something to
     * be confident about from the outside, so this settles it by getting the
     * window out of the way rather than by reasoning. If buttons start working
     * with this on, that was the cause.
     */
    @Volatile var duck = false

    /**
     * How far a held finger wanders, in pixels.
     *
     * A hold that never moves emits a down and then nothing, and some engines
     * take that as a finger present but idle — so a button held to keep
     * shooting quietly stops. How much counts as "still moving" is the game's
     * decision, so this is a setting rather than a constant.
     */
    /** How big the crosshair is drawn, as a fraction of the screen's short side. */
    @Volatile var cursorSize: Float = 0.035f
        set(value) {
            field = value
            prefs.edit().putFloat("cursorSize", value).apply()
            view?.cursorSize = value
        }

    @Volatile var holdJitter: Float = 2f
        set(value) {
            field = value
            prefs.edit().putFloat("holdJitter", value).apply()
            TapService.instance?.holdJitter = value
        }

    @Volatile var tapMs: Long = 140
        set(value) {
            field = value
            prefs.edit().putLong("tapMs", value).apply()
            TapService.instance?.tapMs = value
        }

    @Volatile var lastKey: Int = 0
        private set

    @Volatile var keysSeen: Int = 0
        private set

    /** Set while the UI is waiting for a button to bind to a control. */
    @Volatile var learningFor: String? = null

    /** Whether that binding is a strip's backwards direction. */
    @Volatile var learningPrev: Boolean = false

    var onLearned: ((String, Int) -> Unit)? = null
    var onChanged: (() -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLog.install(this)
        store = Store(this)
        focusAllowed = prefs.getBoolean("focusAllowed", false)
        tapMs = prefs.getLong("tapMs", 140)
        holdJitter = prefs.getFloat("holdJitter", 2f)
        cursorSize = prefs.getFloat("cursorSize", 0.035f)
        settings = settings.copy(
            maxSpeed = prefs.getFloat("sensitivity", settings.maxSpeed),
            holdMs = prefs.getInt("aimHoldMs", settings.holdMs),
        )
        duck = prefs.getBoolean("duck", false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EDIT -> setMode(editing = true)
            ACTION_PLAY -> setMode(editing = false)
            ACTION_TOGGLE -> {
                // Any duck belongs to the mode that is ending.
                forgetHeld()
                setMode(editing = !editing)
            }
            ACTION_MARKERS -> {
                markers = !markers
                view?.showMarkers = markers
                refreshNotification()
            }
            ACTION_SENSITIVITY -> setSensitivity(intent.getFloatExtra(EXTRA_VALUE, 2.2f))
            ACTION_CURSOR_SIZE -> cursorSize = intent.getFloatExtra(EXTRA_VALUE, 0.035f)
            ACTION_JITTER -> holdJitter = intent.getFloatExtra(EXTRA_VALUE, 2f)
            ACTION_TAP_MS -> tapMs = intent.getFloatExtra(EXTRA_VALUE, 140f).toLong()
            ACTION_AIM_HOLD -> {
                val ms = intent.getFloatExtra(EXTRA_VALUE, 220f).toInt()
                settings = settings.copy(holdMs = ms)
                prefs.edit().putInt("aimHoldMs", ms).apply()
            }
            ACTION_DUCK -> {
                duck = !duck
                prefs.edit().putBoolean("duck", duck).apply()
            }
            else -> {
                startForeground(NOTIFICATION, notification())
                setMode(editing = false)
                startSticks()
                // Visible from the start rather than from the first nudge:
                // an invisible crosshair reads as a broken one.
                main.post { showCursorNow() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        teardown()
        instance = null
        super.onDestroy()
    }

    fun setSensitivity(value: Float) {
        val clamped = value.coerceIn(SENSITIVITY_RANGE.start, SENSITIVITY_RANGE.endInclusive)
        settings = settings.copy(maxSpeed = clamped)
        prefs.edit().putFloat("sensitivity", clamped).apply()
    }

    /** Drags one end of a strip, leaving the other exactly where it is. */
    private fun resizeStrip(id: String, movingLowEnd: Boolean, to: Float) {
        val control = layout[id] ?: return
        val (centre, length) = Strip.resizeFromEnd(control, movingLowEnd, to)
        update(
            layout.replace(
                if (control.vertical) {
                    control.copy(y = centre, height = length)
                } else {
                    control.copy(x = centre, width = length)
                }
            )
        )
    }

    private fun moveControl(id: String, x: Float, y: Float) {
        val control = layout[id] ?: return
        update(layout.replace(control.movedTo(x, y)))
    }

    fun reload() {
        view?.layout = store.load()
    }

    val layout: Layout get() = view?.layout ?: store.load()

    fun update(layout: Layout) {
        store.save(layout)
        view?.layout = layout
        onChanged?.invoke()
    }

    val showing: Boolean get() = view != null

    // ---------------------------------------------------------------- window

    /**
     * Rebuilds the window, because the two modes need different flags and a
     * window's flags cannot meaningfully be changed under it.
     *
     * Editing: touchable, so controls can be dragged.
     * Playing: not touchable, so every touch goes to the game — but still
     * focusable, because that is the only way the buttons arrive.
     */
    private fun setMode(editing: Boolean) {
        this.editing = editing
        if (!canDraw(this)) return
        // The notification is the only thing visible from inside a game, so it
        // has to carry the mode rather than a fixed label.
        refreshNotification()

        val wm = manager ?: (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?: return
        manager = wm

        val existing = view
        val target = existing ?: OverlayView(this).apply {
            layout = store.load()
            showMarkers = markers
            cursorSize = this@OverlayService.cursorSize
            onMotion = { event -> this@OverlayService.onMotion(event) }
            onKey = { event -> this@OverlayService.onFocusedKey(event) }
            onMoved = ::moveControl
            onStripEnd = ::resizeStrip
            onPicked = { id ->
                selectedId = id
                // Tapping a control on the overlay always arms its main
                // binding; a strip's backwards direction is set from the app,
                // where there is room to say which is which.
                this@OverlayService.learningPrev = false
                // Picking a control in edit mode arms it: the next gamepad
                // button pressed binds to it. All the editing happens here,
                // over the game, so going back to the app to bind was the
                // wrong way round.
                this@OverlayService.learningFor = id
            }
            onDone = { setMode(editing = false) }
        }
        target.editing = editing

        if (existing != null) {
            try {
                wm.removeView(existing)
            } catch (e: Throwable) {
                // Already gone; adding it again is still the right move.
            }
        }

        try {
            wm.addView(target, params(editing))
            view = target
        } catch (e: Throwable) {
            view = null
        }
    }

    private fun params(editing: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        // Focus is taken only when it buys something: below Android 14 a
        // focused window is the one thing that can see an analog stick, so a
        // stick control cannot work without it. Otherwise buttons arrive
        // through the accessibility key hook and there is no reason to take
        // focus off the game at all.
        if (!needsFocus()) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        if (!editing) {
            // Playing: every touch passes straight through to the game.
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Android 12 blocks touches that pass beneath an overlay owned by
            // another app when that overlay is more opaque than this. The
            // window is mostly transparent anyway, but the system judges it on
            // the window's alpha, not on what was painted — so a fully opaque
            // window of nothing silently eats every finger aimed at the game.
            if (!editing) alpha = 0.8f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /**
     * Whether a focused window is currently the only way to read the sticks.
     *
     * Only when there is nothing better. Focus is the last route on the ladder
     * precisely because it is the one that can stop the game working.
     */
    fun needsFocus(): Boolean =
        layout.usesSticks && source() == StickSource.FOCUSED_OVERLAY

    fun source(): StickSource =
        if (!layout.usesSticks) StickSource.NONE else Sticks.best(focusAllowed)

    private val sticks by lazy { StickClient(this) }

    /**
     * Reads coming back from the reader running as shell.
     *
     * Fed into exactly the same path as a motion event, so there is one place
     * that decides what a stick does and three places a reading can come from.
     */
    private val fromShell = object : IStickCallback.Stub() {
        override fun onStick(x: Float, y: Float) {
            // Posted rather than handled here. This arrives on a Binder thread,
            // while key events arrive on the main one — and both touch the same
            // maps of who is held, who is following the crosshair and which
            // controls are lit. Two threads walking one HashMap is a crash, and
            // it was: pressing a button while the stick was moving threw from
            // inside the iteration.
            //
            // One thread for all of it beats a concurrent collection per field,
            // because the next thing added would have to remember the rule too.
            main.post { onStickValues(x, y) }
        }

        override fun onFailed(why: String?) {
            shellReport = why ?: "reader stopped"
        }
    }

    @Volatile var shellReport: String = "not started"
        private set

    /** Starts whichever stick route is available, or none. */
    private fun startSticks() {
        if (!layout.usesSticks) return
        if (source() != StickSource.SHIZUKU) return
        sticks.onLost = { retrySticks() }
        val stick = layout.sticks().firstOrNull()?.stick ?: Stick.RIGHT
        sticks.start(stick, fromShell)
        shellReport = sticks.report
    }

    private var stickRetries = 0

    /**
     * Starts the reader again after its process went away.
     *
     * Backed off and capped rather than retried forever: if Shizuku itself has
     * stopped, every attempt is a prompt-free failure, and a loop of those is
     * just battery. The count resets whenever a start succeeds.
     */
    private fun retrySticks() {
        if (stickRetries >= 5) {
            shellReport = "reader kept going away; start Shizuku again"
            return
        }
        val wait = 1500L * (stickRetries + 1)
        stickRetries++
        shellReport = "reader went away, retrying in ${wait / 1000}s"
        main.postDelayed({
            if (!sticks.connected) {
                sticks.disconnect()
                startSticks()
                if (sticks.connected) stickRetries = 0
            }
        }, wait)
    }

    private fun stopSticks() {
        sticks.stop()
    }

    /**
     * Whether the overlay may take window focus to read a stick.
     *
     * Off by default, and it stays off until someone deliberately turns it on.
     * Taking focus does not merely cost the back button, as first assumed — a
     * game that loses window focus commonly mutes and pauses, so the overlay
     * going up silently stopped NIKKE responding to anything at all. That is
     * far too large a cost to opt someone into for a feature they may not be
     * using.
     */
    var focusAllowed: Boolean = false
        set(value) {
            field = value
            prefs.edit().putBoolean("focusAllowed", value).apply()
            if (showing) setMode(editing)
        }

    private val prefs by lazy {
        getSharedPreferences("thorpad", Context.MODE_PRIVATE)
    }

    /**
     * A key that arrived because this window holds focus.
     *
     * The cost of focus is that *everything* comes here, including keys the
     * game was meant to get. Rather than swallowing them, the ones that have a
     * system equivalent are performed outright — which is almost all of the
     * ones that matter.
     */
    private fun onFocusedKey(event: KeyEvent): Boolean {
        if (isFromPad(event)) return onKey(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true

        val tapper = TapService.instance
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK ->
                tapper?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            KeyEvent.KEYCODE_HOME ->
                tapper?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            KeyEvent.KEYCODE_APP_SWITCH ->
                tapper?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            KeyEvent.KEYCODE_VOLUME_UP -> adjustVolume(1)
            KeyEvent.KEYCODE_VOLUME_DOWN -> adjustVolume(-1)
            else -> return true
        }
        return true
    }

    private fun adjustVolume(direction: Int) {
        val audio = getSystemService(android.media.AudioManager::class.java) ?: return
        audio.adjustStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            if (direction > 0) android.media.AudioManager.ADJUST_RAISE
            else android.media.AudioManager.ADJUST_LOWER,
            android.media.AudioManager.FLAG_SHOW_UI,
        )
    }

    /**
     * Shrinks the window to a pixel and puts it back.
     *
     * Resized rather than removed and re-added: adding a window back costs a
     * relayout of everything beneath it, and doing that on every button press
     * would be visible as a stutter in the game.
     */
    /**
     * @param forMs how long to stay out of the way, or null to stay until
     *   [unduck] is called. A held button needs the latter: restoring the
     *   overlay part-way through a hold puts it back over the finger that is
     *   still down, and if ducking was what made the touch land in the first
     *   place, the hold simply ends there.
     */
    private fun duckAway(forMs: Long?) {
        val wm = manager ?: return
        val target = view ?: return
        // A hold ducks with no timer and relies on its key-up to restore the
        // window. If that key-up never arrives — the screen slept mid-hold, or
        // the accessibility service was restarted under it — the overlay stays
        // one pixel wide for ever and the crosshair is simply gone. So there is
        // always a backstop, however the duck was asked for.
        main.removeCallbacks(forceBack)
        main.postDelayed(forceBack, MAX_DUCK_MS)
        // A second press while one is already ducked must not schedule a
        // restore of its own: the first one to come back would surface the
        // overlay under the other's finger.
        ducking++
        if (ducked) {
            if (forMs != null) main.postDelayed({ unduck() }, forMs)
            return
        }
        ducked = true

        try {
            wm.updateViewLayout(target, params(editing).apply {
                width = 1
                height = 1
            })
        } catch (e: Throwable) {
            ducked = false
            ducking--
            return
        }

        if (forMs != null) main.postDelayed({ unduck() }, forMs)
    }

    /** Puts the overlay back, once nothing is still holding it down. */
    private fun unduck() {
        if (ducking > 0) ducking--
        if (ducking > 0 || !ducked) return
        ducked = false
        val back = view ?: return
        try {
            manager?.updateViewLayout(back, params(editing))
        } catch (e: Throwable) {
            // The window went away underneath us; nothing to restore.
        }
    }

    @Volatile private var ducking = 0

    /**
     * Longest a duck may last before it is undone regardless.
     *
     * Nobody holds a trigger for eight seconds, and a stuck duck costs the
     * whole overlay — the crosshair, the markers, everything.
     */
    private val MAX_DUCK_MS = 8000L

    private val forceBack = Runnable {
        if (ducked) {
            ducking = 0
            ducked = false
            val back = view ?: return@Runnable
            try {
                manager?.updateViewLayout(back, params(editing))
            } catch (e: Throwable) {
                // The window went away underneath us; nothing to restore.
            }
        }
    }

    /**
     * Forgets every finger the service thinks is down.
     *
     * Called when the thing that was holding them has been replaced — the
     * accessibility service restarting takes its gestures with it, and holds
     * recorded against the old one would never be released.
     */
    fun forgetHeld() {
        holding.clear()
        followingCursor.clear()
        stripAt.clear()
        main.removeCallbacks(forceBack)
        ducking = 0
        if (ducked) forceBackNow()
    }

    private fun forceBackNow() {
        ducked = false
        val back = view ?: return
        try {
            manager?.updateViewLayout(back, params(editing))
        } catch (e: Throwable) {
            // Already gone.
        }
    }

    @Volatile private var ducked = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun teardown() {
        TapService.instance?.releaseEverything()
        // Every hold that was keeping the overlay down is gone with it, so the
        // count has to go too or the window never comes back.
        while (ducking > 0) unduck()
        holding.clear()
        followingCursor.clear()
        stripAt.clear()
        cursors.clear()
        drags.clear()
        stopSticks()
        val wm = manager
        val target = view
        view = null
        manager = null
        if (wm != null && target != null) {
            try {
                wm.removeView(target)
            } catch (e: Throwable) {
                // Already gone.
            }
        }
    }

    // ------------------------------------------------------------- the pad

    /**
     * Handles a gamepad button, from the accessibility service's key hook.
     *
     * Returns whether it was consumed. A bound button is swallowed so the game
     * does not also act on it; everything else — back, volume, an unbound pad
     * button — is handed straight back untouched.
     */
    fun onKey(event: KeyEvent): Boolean {
        if (!isFromPad(event)) return false

        keysSeen++
        lastKey = event.keyCode

        // While a control is picked in edit mode, the next button pressed binds
        // to it. Editing happens on the overlay, over the game, so having to go
        // back to the app to bind was the wrong way round.
        val learning = learningFor
        if (learning != null && event.action == KeyEvent.ACTION_DOWN) {
            learningFor = null
            val bound = if (learningPrev) {
                layout.bindPrev(learning, event.keyCode)
            } else {
                layout.bind(learning, event.keyCode)
            }
            learningPrev = false
            update(bound)
            view?.flash(learning)
            onLearned?.invoke(learning, event.keyCode)
            return true
        }

        // A strip is checked first: its buttons are bindings on a control that
        // is not a plain button, so forKey deliberately does not find them.
        layout.stripFor(event.keyCode)?.let { (strip, delta) ->
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                stepStrip(strip, delta)
            }
            return true
        }

        val control = layout.forKey(event.keyCode) ?: return false
        val tapper = TapService.instance ?: return false

        // A release button ends an aim rather than touching anything itself.
        if (control.releasesAim) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                view?.flash(control.id)
                releaseAim()
            }
            return true
        }

        val bounds = view
        val width = (bounds?.width ?: 0).toFloat()
        val height = (bounds?.height ?: 0).toFloat()
        if (width <= 0f || height <= 0f) return false

        // A button set to fire at the crosshair aims there instead of at its
        // own spot, which is the only thing that makes a cursor do anything.
        val spot = if (control.atCursor) cursorSpot() else null
        val x = (spot?.first ?: control.x) * width
        val y = (spot?.second ?: control.y) * height

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Repeat events arrive while a button is held; only the first
                // should do anything, or a held button becomes a machine gun.
                if (event.repeatCount > 0) return true
                view?.flash(control.id)
                tapper.tapMs = tapMs
                when (control.press) {
                    Press.TAP -> {
                        if (duck) duckAway(tapMs + 80)
                        tapper.tap(x, y)
                    }
                    Press.HOLD -> {
                        // Ducked with no timer: the overlay stays out of the
                        // way for exactly as long as the button is held, and
                        // comes back on release.
                        if (duck) duckAway(null)
                        holding[event.keyCode] = control.id
                        tapper.holdJitter = holdJitter
                        // startDrag rather than startHold when it is following
                        // the crosshair: the same finger, but one whose
                        // destination can still change.
                        if (control.atCursor && cursorSpot() != null) {
                            followingCursor.add(control.id)
                            tapper.startDrag(control.id, x, y)
                            // Drawn the moment it is being aimed by, so a
                            // crosshair nobody has nudged yet is still visible
                            // to aim with.
                            showCursorNow()
                        } else {
                            tapper.startHold(control.id, x, y)
                        }
                    }
                }
            }

            KeyEvent.ACTION_UP -> {
                holding.remove(event.keyCode)?.let {
                    followingCursor.remove(it)
                    tapper.releaseHold(it)
                    if (duck) unduck()
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------- sticks

    private val cursors = mutableMapOf<String, CursorEngine>()
    private val drags = mutableMapOf<String, DragStick>()
    private var lastTick = 0L

    @Volatile var motionSeen: Int = 0
        private set
    @Volatile var lastStick: String = "—"
        private set

    var settings: AimSettings = AimSettings()

    /**
     * A stick moved. Runs every bound stick control and drags its finger.
     *
     * Paced by the events themselves rather than by a timer: a pad reports
     * while it is being moved and goes quiet when it is not, which is exactly
     * when there is and is not work to do.
     */
    fun onMotion(event: android.view.MotionEvent) {
        val eventSource = event.source
        val fromPad =
            eventSource and android.view.InputDevice.SOURCE_JOYSTICK != 0 ||
                eventSource and android.view.InputDevice.SOURCE_GAMEPAD != 0
        if (!fromPad) return

        val bound = layout.sticks()
        if (bound.isEmpty()) return

        // Pads disagree about where the right stick lives, so whichever pair is
        // actually moving is taken to be it.
        val which = bound.first().stick ?: Stick.RIGHT
        if (which == Stick.LEFT) {
            onStickValues(
                event.getAxisValue(android.view.MotionEvent.AXIS_X),
                event.getAxisValue(android.view.MotionEvent.AXIS_Y),
            )
            return
        }
        val rx = event.getAxisValue(android.view.MotionEvent.AXIS_RX)
        val ry = event.getAxisValue(android.view.MotionEvent.AXIS_RY)
        val z = event.getAxisValue(android.view.MotionEvent.AXIS_Z)
        val rz = event.getAxisValue(android.view.MotionEvent.AXIS_RZ)
        val useZ = (kotlin.math.abs(z) + kotlin.math.abs(rz)) >
            (kotlin.math.abs(rx) + kotlin.math.abs(ry))
        onStickValues(if (useZ) z else rx, if (useZ) rz else ry)
    }

    /**
     * One stick reading, already normalised to -1..1.
     *
     * The single place a stick does anything, so a reading off evdev and a
     * reading off a motion event cannot drift into behaving differently.
     */
    fun onStickValues(sx: Float, sy: Float) {
        val sticksBound = layout.sticks()
        if (sticksBound.isEmpty()) return

        motionSeen++
        lastStick = "%+.2f,%+.2f".format(sx, sy)

        val bounds = view ?: return
        val width = bounds.width.toFloat()
        val height = bounds.height.toFloat()
        if (width <= 0f || height <= 0f) return

        val now = android.os.SystemClock.uptimeMillis()
        var dt = (now - lastTick) / 1000f
        lastTick = now
        // A first reading, or one after a long quiet spell, must not teleport
        // the finger across the screen.
        if (dt <= 0f || dt > 0.25f) dt = 0.016f

        val tapper = TapService.instance ?: return

        for (control in sticksBound) {
            val tuned = settings.within(control.region())

            if (control.isDragStick) {
                val drag = drags.getOrPut(control.id) { DragStick(tuned) }
                drag.reconfigure(tuned)
                val step = drag.step(sx, sy, now)
                val px = step.x * width
                val py = step.y * height

                when (step.action) {
                    DragStick.Action.PRESS -> {
                        view?.flash(control.id)
                        tapper.holdJitter = 0f
                        tapper.startDrag(control.id, px, py)
                    }
                    DragStick.Action.MOVE -> tapper.dragTo(control.id, px, py)
                    DragStick.Action.LIFT -> tapper.releaseHold(control.id)
                    DragStick.Action.NONE -> Unit
                }
                continue
            }

            // Nothing is injected by the crosshair itself. It moves, and a
            // button firing "at cursor" is what touches the screen.
            run {
                val cursor = cursors.getOrPut(control.id) {
                    CursorEngine(tuned).apply { centre() }
                }
                cursor.reconfigure(tuned)
                if (cursor.step(sx, sy, dt)) {
                    view?.showCursor(cursor.x, cursor.y)
                    // Anything held at the crosshair travels with it, so one
                    // finger holds *and* aims.
                    if (followingCursor.isNotEmpty()) {
                        val px = cursor.x * width
                        val py = cursor.y * height
                        for (id in followingCursor.toList()) tapper.dragTo(id, px, py)
                    }
                }
            }

        }
    }

    /** Which slot each strip is on, or -1 for nothing chosen yet. */
    private val stripAt = mutableMapOf<String, Int>()

    /**
     * Moves a strip one slot and taps it.
     *
     * The tap is the whole point — stepping a marker around without touching
     * anything would leave the game exactly where it was.
     */
    private fun stepStrip(control: Control, delta: Int) {
        val tapper = TapService.instance ?: return
        val bounds = view ?: return
        if (bounds.width <= 0 || bounds.height <= 0) return

        val next = Strip.step(stripAt[control.id] ?: -1, delta, control.slots)
        stripAt[control.id] = next

        val points = Strip.slotPoints(control)
        val point = points.getOrNull(next) ?: return

        bounds.showStrip(control.id, next)
        if (duck) duckAway(tapMs + 80)
        tapper.tapMs = tapMs
        tapper.tap(point.first * bounds.width, point.second * bounds.height)
    }

    /**
     * Lifts every aim stick that is currently holding.
     *
     * The deliberate end of an aim, for a weapon that fires when the finger
     * comes up — where centring the stick to steady a shot must not be the
     * thing that takes the shot.
     */
    fun releaseAim() {
        val tapper = TapService.instance ?: return
        for ((id, drag) in drags) {
            drag.release() ?: continue
            tapper.releaseHold(id)
        }
    }

    /**
     * Where a button set to fire "at cursor" should aim, in fractions.
     *
     * Creates the crosshair if the stick has not been touched yet. It used to
     * wait for the first stick movement, which got the order exactly backwards:
     * you hold the trigger to zoom and *then* aim, so at the moment the trigger
     * went down there was no crosshair, the finger went down stationary, and it
     * never followed. A bound crosshair has a position from the start now,
     * centred in its own region.
     */
    private fun cursorSpot(): Pair<Float, Float>? {
        val control = layout.cursor() ?: return null
        val cursor = cursors.getOrPut(control.id) {
            CursorEngine(settings.within(control.region())).apply { centre() }
        }
        return cursor.x to cursor.y
    }

    /** Puts the crosshair on screen as soon as there is one to show. */
    private fun showCursorNow() {
        val spot = cursorSpot() ?: return
        view?.showCursor(spot.first, spot.second)
        // Anything already held at it jumps to where it actually is, rather
        // than staying wherever it was pressed.
        if (followingCursor.isNotEmpty()) {
            val bounds = view ?: return
            val tapper = TapService.instance ?: return
            for (id in followingCursor.toList()) {
                tapper.dragTo(id, spot.first * bounds.width, spot.second * bounds.height)
            }
        }
    }

    private fun isFromPad(event: KeyEvent): Boolean {
        val source = event.source
        return source and android.view.InputDevice.SOURCE_GAMEPAD != 0 ||
            source and android.view.InputDevice.SOURCE_JOYSTICK != 0 ||
            source and android.view.InputDevice.SOURCE_DPAD != 0
    }

    // ------------------------------------------------------------ housekeeping

    private fun refreshNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(NOTIFICATION, notification())
        } catch (e: Throwable) {
            // Not in the foreground yet; the first startForeground carries it.
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Controls", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(label: String, what: String, code: Int) =
            Notification.Action.Builder(
                null as android.graphics.drawable.Icon?,
                label,
                PendingIntent.getService(
                    this, code,
                    Intent(this, OverlayService::class.java).setAction(what),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build()

        return Notification.Builder(this, CHANNEL)
            .setContentTitle(if (editing) "thorpad — EDITING" else "thorpad — live")
            .setContentText(
                if (editing) {
                    "Buttons won't reach the game while editing."
                } else {
                    "Buttons are tapping the game."
                }
            )
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            // From inside a game this is the only way to flip modes without
            // leaving it, which is most of when you want to.
            .addAction(action(if (editing) "Play" else "Edit", ACTION_TOGGLE, 1))
            .addAction(action("Stop", ACTION_STOP, 2))
            .setOngoing(true)
            .build()
    }
}

/** The layout on disk. */
class Store(private val context: Context) {
    private val prefs by lazy {
        context.getSharedPreferences("thorpad", Context.MODE_PRIVATE)
    }

    fun load(): Layout = Layout.fromJson(prefs.getString(KEY, null))

    fun save(layout: Layout) {
        prefs.edit().putString(KEY, layout.toJson()).apply()
    }

    private companion object {
        const val KEY = "layout.v1"
    }
}
