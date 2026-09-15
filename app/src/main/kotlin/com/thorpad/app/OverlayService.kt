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

        private const val CHANNEL = "thorpad"
        private const val NOTIFICATION = 1

        fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun send(context: Context, action: String) {
            val intent = Intent(context, OverlayService::class.java).setAction(action)
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

    @Volatile var editing = false
        private set

    @Volatile var lastKey: Int = 0
        private set

    @Volatile var keysSeen: Int = 0
        private set

    /** Set while the UI is waiting for a button to bind to a control. */
    @Volatile var learningFor: String? = null

    var onLearned: ((String, Int) -> Unit)? = null
    var onChanged: (() -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
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
            else -> {
                startForeground(NOTIFICATION, notification())
                setMode(editing = false)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        teardown()
        instance = null
        super.onDestroy()
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

        val wm = manager ?: (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?: return
        manager = wm

        val existing = view
        val target = existing ?: OverlayView(this).apply {
            layout = store.load()
            onMotion = { event -> this@OverlayService.onMotion(event) }
            onKey = { event -> this@OverlayService.onFocusedKey(event) }
            onMoved = ::moveControl
            onPicked = { id ->
                selectedId = id
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /** Whether a focused window is currently the only way to read the sticks. */
    fun needsFocus(): Boolean =
        Build.VERSION.SDK_INT < 34 && layout.usesSticks && focusAllowed

    /** Turned on by the user, because it is a trade rather than a free win. */
    var focusAllowed: Boolean = true

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

    private fun teardown() {
        TapService.instance?.releaseEverything()
        holding.clear()
        aim.clear()
        cursors.clear()
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
            val bound = layout.bind(learning, event.keyCode)
            update(bound)
            view?.flash(learning)
            onLearned?.invoke(learning, event.keyCode)
            return true
        }

        val control = layout.forKey(event.keyCode) ?: return false
        val tapper = TapService.instance ?: return false

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
                when (control.press) {
                    Press.TAP -> tapper.tap(x, y)
                    Press.HOLD -> {
                        holding[event.keyCode] = control.id
                        tapper.startHold(control.id, x, y)
                    }
                }
            }

            KeyEvent.ACTION_UP -> {
                holding.remove(event.keyCode)?.let { tapper.releaseHold(it) }
            }
        }
        return true
    }

    // ------------------------------------------------------------- sticks

    private val aim = mutableMapOf<String, AimEngine>()
    private val cursors = mutableMapOf<String, CursorEngine>()
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
        val source = event.source
        val fromPad =
            source and android.view.InputDevice.SOURCE_JOYSTICK != 0 ||
                source and android.view.InputDevice.SOURCE_GAMEPAD != 0
        if (!fromPad) return

        val sticks = layout.sticks()
        if (sticks.isEmpty()) return

        motionSeen++

        val bounds = view ?: return
        val width = bounds.width.toFloat()
        val height = bounds.height.toFloat()
        if (width <= 0f || height <= 0f) return

        val now = android.os.SystemClock.uptimeMillis()
        var dt = (now - lastTick) / 1000f
        lastTick = now
        // A first event, or one after a long quiet spell, must not teleport the
        // finger across the screen.
        if (dt <= 0f || dt > 0.25f) dt = 0.016f

        val tapper = TapService.instance ?: return

        for (control in sticks) {
            val which = control.stick ?: continue
            val sx: Float
            val sy: Float
            if (which == Stick.LEFT) {
                sx = event.getAxisValue(android.view.MotionEvent.AXIS_X)
                sy = event.getAxisValue(android.view.MotionEvent.AXIS_Y)
            } else {
                // Pads disagree about where the right stick lives. Whichever
                // pair is actually moving is the one that is it.
                val rx = event.getAxisValue(android.view.MotionEvent.AXIS_RX)
                val ry = event.getAxisValue(android.view.MotionEvent.AXIS_RY)
                val z = event.getAxisValue(android.view.MotionEvent.AXIS_Z)
                val rz = event.getAxisValue(android.view.MotionEvent.AXIS_RZ)
                val useZ = (kotlin.math.abs(z) + kotlin.math.abs(rz)) >
                    (kotlin.math.abs(rx) + kotlin.math.abs(ry))
                sx = if (useZ) z else rx
                sy = if (useZ) rz else ry
            }

            lastStick = "%s %+.2f,%+.2f".format(which.name.lowercase(), sx, sy)

            val tuned = settings.within(control.region())

            if (control.isCursor) {
                // Nothing is injected here at all. The crosshair moves, and a
                // button firing "at cursor" is what eventually touches the
                // screen — which is the whole point of this kind.
                val cursor = cursors.getOrPut(control.id) {
                    CursorEngine(tuned).apply { centre() }
                }
                cursor.reconfigure(tuned)
                if (cursor.step(sx, sy, dt)) {
                    view?.showCursor(cursor.x, cursor.y)
                }
                continue
            }

            val engine = aim.getOrPut(control.id) {
                AimEngine(tuned)
            }
            engine.reconfigure(tuned)

            val step = engine.step(sx, sy, dt, now)
            val px = step.x * width
            val py = step.y * height

            when (step.action) {
                AimEngine.Action.PRESS -> {
                    view?.flash(control.id)
                    tapper.startDrag(control.id, px, py)
                }
                AimEngine.Action.MOVE -> tapper.dragTo(control.id, px, py)
                AimEngine.Action.LIFT -> tapper.releaseHold(control.id)
                AimEngine.Action.RESTART -> {
                    // Out of screen to drag across: lift, go back to the far
                    // side and press again. The hitch you feel on a long sweep.
                    tapper.releaseHold(control.id)
                    tapper.startDrag(control.id, px, py)
                }
                AimEngine.Action.NONE -> Unit
            }
        }
    }

    /** Where a button set to fire "at cursor" should aim, in fractions. */
    private fun cursorSpot(): Pair<Float, Float>? {
        val control = layout.cursor() ?: return null
        val cursor = cursors[control.id] ?: return null
        return cursor.x to cursor.y
    }

    private fun isFromPad(event: KeyEvent): Boolean {
        val source = event.source
        return source and android.view.InputDevice.SOURCE_GAMEPAD != 0 ||
            source and android.view.InputDevice.SOURCE_JOYSTICK != 0 ||
            source and android.view.InputDevice.SOURCE_DPAD != 0
    }

    // ------------------------------------------------------------ housekeeping

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
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL)
            .setContentTitle("thorpad controls are on")
            .setContentText("Tap to edit, or stop them here.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop", stop)
                    .build()
            )
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
