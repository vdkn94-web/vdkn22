package com.splayer.splay

import android.content.Context
import android.util.Log
import java.io.File

object SPlayLog {
    private const val TAG = "SPlay"
    private const val MAX_BYTES = 1_500_000L
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                error("Crash", "Uncaught exception on ${thread.name}", error, context)
                previous?.uncaughtException(thread, error)
            }
            installed = true
            info("App", "Crash-safe file logging initialized", context)
        }
    }

    fun info(area: String, message: String, context: Context? = null) = write("I", area, message, null, context)
    fun warn(area: String, message: String, error: Throwable? = null, context: Context? = null) = write("W", area, message, error, context)
    fun error(area: String, message: String, error: Throwable? = null, context: Context? = null) = write("E", area, message, error, context)

    private fun write(level: String, area: String, message: String, error: Throwable?, context: Context?) {
        val line = "${System.currentTimeMillis()} $level/$area: $message" +
            (error?.let { "\n${Log.getStackTraceString(it)}" } ?: "") + "\n"
        runCatching {
            val app = context?.applicationContext ?: AppContextHolder.context ?: return@runCatching
            val target = file(app)
            synchronized(this) {
                if (target.exists() && target.length() > MAX_BYTES) {
                    val old = File(target.parentFile, "splay-logs.previous.txt")
                    old.delete()
                    target.renameTo(old)
                }
                target.appendText(line)
            }
        }
        when (level) {
            "E" -> Log.e(TAG, line, error)
            "W" -> Log.w(TAG, line, error)
            else -> Log.i(TAG, line)
        }
    }

    fun file(context: Context): File = File(context.filesDir, "splay-logs.txt")
}

internal object AppContextHolder {
    @Volatile var context: Context? = null
}
