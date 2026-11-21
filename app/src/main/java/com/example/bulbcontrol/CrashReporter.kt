package com.example.bulbcontrol

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Saves the stack trace (plus the recent connection log) when the app crashes,
 * so it can be shown and copied the next time the app opens.
 */
object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"

    /** Recent diagnostic lines, filled by BleBulbLink. */
    val recentLog = ArrayDeque<String>()

    fun record(line: String) {
        synchronized(recentLog) {
            recentLog.addLast(line)
            while (recentLog.size > 60) recentLog.removeFirst()
        }
    }

    fun install(context: Context) {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val log = synchronized(recentLog) { recentLog.joinToString("\n") }
                file.writeText(
                    "Bulb Control crash on thread ${thread.name}\n" +
                        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                        "${Build.MANUFACTURER} ${Build.MODEL}\n\n$trace\n--- Recent log ---\n$log\n"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Returns the saved crash report (if any) and deletes it. */
    fun takeLastCrash(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull().also { file.delete() }
    }
}
