package com.thorpad.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * Where a stick reading can come from, and which to prefer.
 *
 * Three routes, none of them available everywhere:
 *
 * - **Motion events** (Android 14+). The system hands them over: no window, no
 *   focus, no cost. Nothing to set up. Not available here below 14.
 * - **Shizuku** reading `/dev/input/event*` as shell. Also costs the game
 *   nothing, because the kernel has no concept of focus — but it needs Shizuku
 *   started, which is a reboot-persistent chore.
 * - **A focused overlay**. Always available, and the worst: a game that loses
 *   window focus commonly mutes and pauses, so this can stop the game
 *   responding at all.
 *
 * Preferred in that order, and the app says which one it ended up on, because
 * the difference between them is the difference between a game that works and
 * one that goes silent.
 */
enum class StickSource {
    MOTION_EVENTS,
    SHIZUKU,
    FOCUSED_OVERLAY,
    NONE;

    val label: String
        get() = when (this) {
            MOTION_EVENTS -> "system motion events (free)"
            SHIZUKU -> "Shizuku reading /dev/input (free)"
            FOCUSED_OVERLAY -> "focused overlay — the game may mute"
            NONE -> "nothing — sticks will not move"
        }
}

object Sticks {

    const val REQUEST_CODE = 7211

    fun motionEventsAvailable(): Boolean = Build.VERSION.SDK_INT >= 34

    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun shizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun requestShizuku() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
            // The state check keeps reporting not-ready, which is the honest
            // answer and what the UI shows.
        }
    }

    /** The best route available right now, given what the user has set up. */
    fun best(focusAllowed: Boolean): StickSource = when {
        motionEventsAvailable() -> StickSource.MOTION_EVENTS
        shizukuReady() -> StickSource.SHIZUKU
        focusAllowed -> StickSource.FOCUSED_OVERLAY
        else -> StickSource.NONE
    }
}

/** Holds the connection to the stick reader running as shell. */
class StickClient(private val context: Context) {

    private var service: IStickService? = null
    private var onReady: ((IStickService?) -> Unit)? = null

    var report: String = "not connected"
        private set

    /** Told when the reader's process goes away, so it can be started again. */
    var onLost: (() -> Unit)? = null

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, StickService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("sticks")
        .debuggable(false)
        .version(2)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) {
                IStickService.Stub.asInterface(binder)
            } else {
                null
            }
            onReady?.invoke(service)
            onReady = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            report = "reader disconnected"
            // Sleeping the device can take the reader's process with it, and
            // nothing else would ever notice: the stick simply stops moving the
            // crosshair and the app carries on looking fine.
            onLost?.invoke()
        }
    }

    val connected: Boolean get() = service != null

    fun connect(then: (IStickService?) -> Unit) {
        val existing = service
        if (existing != null) {
            then(existing)
            return
        }
        onReady = then
        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: Throwable) {
            onReady = null
            report = "could not start the reader: ${e.message}"
            then(null)
        }
    }

    fun start(stick: Stick, callback: IStickCallback) {
        connect { reader ->
            report = try {
                reader?.start(
                    callback,
                    if (stick == Stick.LEFT) StickService.STICK_LEFT
                    else StickService.STICK_RIGHT,
                ) ?: "no reader"
            } catch (e: Throwable) {
                "start failed: ${e.message}"
            }
        }
    }

    fun stop() {
        try {
            service?.stop()
        } catch (e: Throwable) {
            // Already gone.
        }
    }

    fun disconnect() {
        stop()
        try {
            Shizuku.unbindUserService(args, connection, true)
        } catch (e: Throwable) {
            // Already gone.
        }
        service = null
    }
}
