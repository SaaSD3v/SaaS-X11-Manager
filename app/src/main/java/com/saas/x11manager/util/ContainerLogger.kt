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
 * libsu's CallbackList may deliver package-manager stdout/stderr from a worker
 * thread and can emit hundreds of lines per second. Mutating Compose snapshot
 * state directly from those callbacks is both unsafe and expensive. Streamed
 * lines are therefore queued and drained on the main looper in short batches.
 * Semantic suspend logs (i/w/e) remain immediate and flush pending stream output
 * first so messages such as "[+] OK" stay after the command output they describe.
 */
class ViewModelLogger(
    private val onLog: (Int, String) -> Unit
) : ContainerLogger() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentLinkedQueue<Pair<Int, String>>()
    private val flushScheduled = AtomicBoolean(false)

    private val flushRunnable = object : Runnable {
        override fun run() {
            flushScheduled.set(false)
            drainPendingOnMain()
        }
    }

    override fun logImmediate(level: Int, msg: String) {
        pending.add(level to msg)
        scheduleFlush()
    }

    override suspend fun i(msg: String) = emitSemantic(Log.INFO, msg)
    override suspend fun w(msg: String) = emitSemantic(Log.WARN, msg)
    override suspend fun e(msg: String) = emitSemantic(Log.ERROR, msg)

    private suspend fun emitSemantic(level: Int, msg: String) {
        withContext(Dispatchers.Main.immediate) {
            drainPendingOnMain()
            onLog(level, msg)
        }
    }

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

        // A producer may append while this drain is running. If it observed the
        // previous scheduled state before it was cleared, ensure that tail still
        // receives a future flush instead of waiting for another log line.
        if (pending.isNotEmpty()) {
            scheduleFlush()
        }
    }

    private companion object {
        const val STREAM_BATCH_WINDOW_MS = 32L
    }
}
