package com.thorpad.app

import kotlin.concurrent.thread

/**
 * Reads the gamepad's sticks from the kernel, as shell.
 *
 * This exists because of a gap in the platform, not because anyone wanted a
 * second privilege model. Analog axes are motion events; before Android 14 an
 * accessibility service cannot see them, and the only window that can is one
 * holding focus — which makes a game mute and pause, so it is not a real
 * option.
 *
 * `/dev/input/event*` has no such problem: the kernel has no concept of focus,
 * and every reader with permission sees every event. The permission is the
 * whole catch — those nodes are `crw-rw---- root input` and an app uid is not
 * in the `input` group. Shell is, which is why `adb shell getevent` works, and
 * Shizuku is what runs this as shell.
 *
 * Only the sticks come through here. Buttons already arrive through the
 * accessibility key hook, which costs nothing and needs no setup, so routing
 * them this way as well would make Shizuku a requirement for the part that
 * works without it.
 */
class StickService : IStickService.Stub() {

    @Volatile private var running = false
    private var reader: Thread? = null
    private val rangeX = AxisRange()
    private val rangeY = AxisRange()
    @Volatile private var report = "not started"

    override fun describe(): String = report

    override fun start(callback: IStickCallback?, stick: Int): String {
        if (callback == null) return "no callback".also { report = it }
        if (running) return report

        val pad = Evdev.findGamepad()
            ?: return "no gamepad in /proc/bus/input/devices".also { report = it }

        // Which pair is the right stick differs by pad; the left is always
        // X/Y. Evdev.Device works the right one out from what the device
        // advertises rather than assuming.
        val axes = if (stick == STICK_LEFT) {
            Evdev.ABS_X to Evdev.ABS_Y
        } else {
            pad.rightStick ?: return "${pad.name} has no right stick".also { report = it }
        }

        running = true
        reader = thread(name = "thorpad-evdev", isDaemon = true) {
            try {
                Evdev.open(pad.path).use { stream ->
                    val buffer = ByteArray(Evdev.EVENT_SIZE * 64)
                    var rawX = 0
                    var rawY = 0
                    while (running) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        var moved = false
                        for (event in Evdev.parse(buffer, read)) {
                            if (event.type != Evdev.EV_ABS) continue
                            when (event.code) {
                                axes.first -> {
                                    rangeX.observe(event.value); rawX = event.value; moved = true
                                }
                                axes.second -> {
                                    rangeY.observe(event.value); rawY = event.value; moved = true
                                }
                            }
                        }
                        if (moved) {
                            // Sent normalised, so the app never has to know
                            // what range this particular pad reports.
                            callback.onStick(rangeX.normalise(rawX), rangeY.normalise(rawY))
                        }
                    }
                }
            } catch (e: Throwable) {
                running = false
                report = "reading ${pad.path} failed: ${e.message}"
                try {
                    callback.onFailed(report)
                } catch (ignored: Throwable) {
                    // The app went away; nothing left to tell.
                }
            }
        }

        report = "reading ${pad.name} at ${pad.path}, axes ${axes.first}/${axes.second}"
        return report
    }

    override fun stop() {
        running = false
        reader?.interrupt()
        reader = null
        report = "stopped"
    }

    override fun destroy() {
        stop()
        System.exit(0)
    }

    companion object {
        const val STICK_LEFT = 0
        const val STICK_RIGHT = 1
    }
}
