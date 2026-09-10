package com.saas.x11manager.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

abstract class ContainerLogger {
    var verbose: Boolean = false
    abstract fun logImmediate(level: Int, msg: String)

    // Logging must never turn a runtime/control helper into a suspend boundary.
    // ViewModelLogger already coalesces delivery onto Main asynchronously, so
    // these semantic helpers can safely be called from native/process code.
    open fun i(msg: String) { logImmediate(Log.INFO, msg) }
    open fun w(msg: String) { logImmediate(Log.WARN, msg) }
    open fun e(msg: String) { logImmediate(Log.ERROR, msg) }
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
    private val stableFacts = mutableSetOf<String>()
    private var uiFlushScheduled = false
    private var graphicalStartSeen = false
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

    override fun i(msg: String) {
        enqueueReduced(reduce(Log.INFO, msg))
    }

    override fun w(msg: String) {
        enqueueReduced(reduce(Log.WARN, msg))
    }

    override fun e(msg: String) {
        enqueueReduced(reduce(Log.ERROR, msg))
    }

    /** Drain the final burst before marking an operation complete or saving its logs. */
    suspend fun flush() = withContext(Dispatchers.Main.immediate) {
        mainHandler.removeCallbacks(flushRunnable)
        flushRunnable.run()
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
        synchronized(reducerLock) {
            reducer.reduce(level, msg)
                .filterNot { (_, text) ->
                    // Replaced by VncConnectionGuide's exact active-session summary.
                    // Keeping these legacy generic lines would either duplicate the
                    // new facts or refer to addresses that the reducer intentionally
                    // did not retain.
                    text == "[VNC] ✓ Connect with any standard VNC client using one of the reachable addresses above" ||
                        text == "[VNC] ✓ Previous Manager-owned VNC runtime cleared"
                }
                .filter { (_, text) -> keepSemanticFact(text) }
        }

    private fun keepSemanticFact(text: String): Boolean {
        if (text == "[SESSION] Starting graphical access") {
            graphicalStartSeen = true
            return true
        }

        // Home knows the chosen mode before SessionAccessManager owns the Start.
        // Do not print that pre-flight copy; retain the same fact when it arrives
        // immediately after the official graphical-start lifecycle marker.
        if (text.startsWith("[SESSION] • Access:") && !graphicalStartSeen) {
            return false
        }

        // The pinned summary must be self-contained even if the same container,
        // desktop or user was already named earlier in the operation.
        if (text == VncConnectionGuide.ACTIVE_SUMMARY_BEGIN) {
            stableFacts.clear()
            return true
        }

        if (!isStableFact(text)) return true
        return stableFacts.add(text)
    }

    private fun isStableFact(text: String): Boolean =
        text.startsWith("[SESSION] • Access:") ||
            text.startsWith("[SESSION] • Desktop:") ||
            text.startsWith("[USER] • Desktop user:") ||
            text.startsWith("[CONTAINER] • Container:")
}
