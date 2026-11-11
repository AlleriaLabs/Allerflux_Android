package com.alleria.allerflux

import android.app.Application
import android.os.Build
import java.io.File

/**
 * Persists uncaught startup exceptions so users can share the exact failure
 * without needing a computer, ADB, or a separate logcat app.
 */
class AllerfluxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val report = buildString {
                    appendLine("Allerflux startup crash report")
                    appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Exception: ${error.javaClass.name}: ${error.message}")
                    appendLine()
                    append(error.stackTraceToString().takeLast(16000))
                }
                File(filesDir, "last_crash.txt").writeText(report)
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }
}
