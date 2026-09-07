package com.saas.x11manager.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * Both semantic checkpoints and streamed command output pass through the same
 * concise reducer before they can reach Compose state. Raw apt/apk output,
 * generated scripts, DroidSpaces banners, [CTX] diagnostics and PulseAudio
 * probes are discarded by the reducer and are never retained behind a hidden
 * details mode.
 *
 * UI delivery is intentionally asynchronous and burst-coalesced. libsu work must
 * never wait for the Compose main thread just because a progress line was emitted.
 * One main-loop callback flushes every reduced entry accumulated during the burst,
 * preserving order while avoiding one Handler post / recomposition trigger per line.
 */
class ViewModelLogger(
    private val onLog: (Int, String) -> Unit
) : ContainerLogger() {
    private val reducer = ConciseLogReducer()
    private val reducerLock = Any()
    private val dispatchLock = Any()
    private val pendingUiEntries = mutableListOf<Pair<Int, String>>()
    private var uiFlushScheduled = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val flushRunnable = Runnable {
        val batch = synchronized(dispatchLock) {
            if (pendingUiEntries.isEmpty()) {
                uiFlushScheduled = false
                emptyList()
            } else {
                val copy = pendingUiEntries.toList()
                pendingUiEntries.clear()
                uiFlushScheduled = false
                copy
            }
        }

        batch.forEach { (level, message) -> onLog(level, message) }
    }

    override fun logImmediate(level: Int, msg: String) {
        enqueueReduced(reduce(level, msg))
    }

    override suspend fun i(msg: String) {
        enqueueReduced(reduce(Log.INFO, msg))
    }

    override suspend fun w(msg: String) {
        enqueueReduced(reduce(Log.WARN, msg))
    }

    override suspend fun e(msg: String) {
        enqueueReduced(reduce(Log.ERROR, msg))
    }

    private fun enqueueReduced(entries: List<Pair<Int, String>>) {
        if (entries.isEmpty()) return

        var scheduleFlush = false
        synchronized(dispatchLock) {
            pendingUiEntries.addAll(entries)
            if (!uiFlushScheduled) {
                uiFlushScheduled = true
                scheduleFlush = true
            }
        }

        if (scheduleFlush) {
            // Always post, even when the caller is already on Main. This gives
            // several lifecycle lines emitted in the same turn one cheap batch
            // and prevents logging itself from extending the user operation.
            mainHandler.post(flushRunnable)
        }
    }

    private fun reduce(level: Int, msg: String): List<Pair<Int, String>> =
        synchronized(reducerLock) { reducer.reduce(level, msg) }
}
