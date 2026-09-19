package com.thorpad.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the last crash down so it can be read back.
 *
 * Worth its weight after several rounds of guessing at a crash from a
 * description. A stack trace names the line; "it crashed after sleep" names a
 * moment, and the two are very different amounts of information.
 *
 * The handler chains to whatever was there before rather than swallowing the
 * throw — the app should still die, it should just leave a note.
 */
object CrashLog {

    private const val FILE = "last-crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return

        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    fun lastCrash(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE)
        return if (file.exists()) file.readText().ifBlank { null } else null
    }

    fun clear(context: Context) {
        File(context.applicationContext.filesDir, FILE).delete()
    }

    private class Handler(
        private val context: Context,
        private val next: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                val trace = StringWriter()
                error.printStackTrace(PrintWriter(trace))
                val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK)
                    .format(Date())
                File(context.filesDir, FILE).writeText(
                    buildString {
                        append("thorpad crashed at ").append(when_).append('\n')
                        append("thread: ").append(thread.name).append('\n')
                        append("android ").append(android.os.Build.VERSION.SDK_INT)
                        append(" · ").append(android.os.Build.MODEL).append("\n\n")
                        append(trace.toString())
                    }
                )
            } catch (ignored: Throwable) {
                // Nothing useful left to do; the app is going down regardless
                // and a throw in here would replace the real cause.
            }
            next?.uncaughtException(thread, error)
        }
    }
}
