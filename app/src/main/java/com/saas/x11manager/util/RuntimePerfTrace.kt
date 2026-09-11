package com.saas.x11manager.util

import android.os.SystemClock
import android.util.Log

/**
 * Lightweight TEST-branch timing trace for physical performance validation.
 *
 * It intentionally records only stage names, elapsed time, operation name and a
 * container/display target. Commands, passwords, cookies and shell output are
 * never included. The trace is Logcat-only and does not enter the user terminal
 * or durable operation log archive.
 */
internal class RuntimePerfTrace(
    private val operation: String,
    private val target: String
) {
    private val startedAt = SystemClock.elapsedRealtimeNanos()

    suspend fun <T> stage(name: String, block: suspend () -> T): T {
        val stageStartedAt = SystemClock.elapsedRealtimeNanos()
        return try {
            block()
        } finally {
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - stageStartedAt) / 1_000_000L
            Log.i(TAG, "operation=$operation target=$target stage=$name elapsed_ms=$elapsedMs")
        }
    }

    fun finish(success: Boolean) {
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L
        Log.i(
            TAG,
            "operation=$operation target=$target complete=${if (success) "success" else "failure"} elapsed_ms=$elapsedMs"
        )
    }

    private companion object {
        const val TAG = "SaaSPerf"
    }
}
