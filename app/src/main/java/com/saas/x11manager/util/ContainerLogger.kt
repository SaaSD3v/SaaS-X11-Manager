package com.saas.x11manager.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

abstract class ContainerLogger {
    var verbose: Boolean = false
    abstract fun logImmediate(level: Int, msg: String)
    open suspend fun i(msg: String) { withContext(Dispatchers.Main.immediate) { logImmediate(Log.INFO, msg) } }
    open suspend fun w(msg: String) { withContext(Dispatchers.Main.immediate) { logImmediate(Log.WARN, msg) } }
    open suspend fun e(msg: String) { withContext(Dispatchers.Main.immediate) { logImmediate(Log.ERROR, msg) } }
}

/**
 * Logger used by Compose ViewModels.
 *
 * Only the concise semantic stream is stored in Compose state. Legacy raw output
 * is reduced before it reaches the UI, so package-manager chatter, generated
 * scripts, [CTX] diagnostics, PulseAudio probes and the DroidSpaces banner do not
 * accumulate behind a hidden "Details" mode.
 */
class ViewModelLogger(
    private val onLog: (Int, String) -> Unit
) : ContainerLogger() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentLinkedQueue<Pair<Int, String>>()
    private val flushScheduled = AtomicBoolean(false)
    private val reducer = ConciseLogReducer()
    private val reducerLock = Any()

    private val flushRunnable = object : Runnable {
        override fun run() {
            flushScheduled.set(false)
            drainPendingOnMain()
        }
    }

    override fun logImmediate(level: Int, msg: String) {
        reduce(level, msg).forEach(pending::add)
        if (pending.isNotEmpty()) scheduleFlush()
    }

    override suspend fun i(msg: String) = emitSemantic(Log.INFO, msg)
    override suspend fun w(msg: String) = emitSemantic(Log.WARN, msg)
    override suspend fun e(msg: String) = emitSemantic(Log.ERROR, msg)

    private suspend fun emitSemantic(level: Int, msg: String) {
        val entries = reduce(level, msg)
        withContext(Dispatchers.Main.immediate) {
            drainPendingOnMain()
            entries.forEach { onLog(it.first, it.second) }
        }
    }

    private fun reduce(level: Int, msg: String): List<Pair<Int, String>> =
        synchronized(reducerLock) { reducer.reduce(level, msg) }

    private fun scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(flushRunnable, STREAM_BATCH_WINDOW_MS)
        }
    }

    private fun drainPendingOnMain() {
        while (true) {
            val entry = pending.poll() ?: break
            onLog(entry.first, entry.second)
        }

        if (pending.isNotEmpty()) {
            scheduleFlush()
        }
    }

    private companion object {
        const val STREAM_BATCH_WINDOW_MS = 32L
    }
}
