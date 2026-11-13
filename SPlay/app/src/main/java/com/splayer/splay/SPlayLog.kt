package com.splayer.splay

import android.content.Context
import android.util.Log
import java.io.File
import java.io.OutputStream

object SPlayLog {
    private const val TAG = "SPlay"
    private const val MAX_BYTES = 1_500_000L
    @Volatile private var installed = false
    @Volatile private var loggingEnabled = true

    fun isEnabled(context: Context): Boolean {
        loggingEnabled = context.getSharedPreferences("splay_logging", Context.MODE_PRIVATE).getBoolean("enabled", true)
        return loggingEnabled
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        loggingEnabled = enabled
        context.getSharedPreferences("splay_logging", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        if (enabled) info("Logs", "Logging enabled", context)
    }

    fun clear(context: Context) {
        synchronized(this) {
            file(context).delete()
            File(context.filesDir, "splay-logs.previous.txt").delete()
        }
        if (isEnabled(context)) info("Logs", "Log cleared", context)
    }

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

    fun debug(area: String, message: String, context: Context? = null) = write("D", area, message, null, context)
    fun info(area: String, message: String, context: Context? = null) = write("I", area, message, null, context)
    fun warn(area: String, message: String, error: Throwable? = null, context: Context? = null) = write("W", area, message, error, context)
    fun error(area: String, message: String, error: Throwable? = null, context: Context? = null) = write("E", area, message, error, context)

    private fun write(level: String, area: String, message: String, error: Throwable?, context: Context?) {
        val appContext = context?.applicationContext ?: AppContextHolder.context
        if (appContext != null && !isEnabled(appContext)) return
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
            "D" -> Log.d(TAG, line)
            else -> Log.i(TAG, line)
        }
    }

    fun file(context: Context): File = File(context.filesDir, "splay-logs.txt")

    fun exportTo(context: Context, output: OutputStream) {
        synchronized(this) {
            File(context.filesDir, "splay-logs.previous.txt").takeIf { it.isFile }?.inputStream()?.use { it.copyTo(output) }
            file(context).takeIf { it.isFile }?.inputStream()?.use { it.copyTo(output) }
        }
    }
}

internal object AppContextHolder {
    @Volatile var context: Context? = null
}
