package com.saas.x11manager.util

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
 * Raw command stdout/stderr is deliberately discarded at the entry point. Only
 * semantic i/w/e checkpoints pass through ConciseLogReducer and reach Compose
 * state. This keeps apt/apk output, generated scripts, DroidSpaces banners,
 * PulseAudio probes and other diagnostic streams from being queued or retained.
 */
class ViewModelLogger(
    private val onLog: (Int, String) -> Unit
) : ContainerLogger() {
    private val reducer = ConciseLogReducer()
    private val reducerLock = Any()

    /** Raw stream callback: intentionally not stored or rendered. */
    override fun logImmediate(level: Int, msg: String) = Unit

    override suspend fun i(msg: String) = emitSemantic(Log.INFO, msg)
    override suspend fun w(msg: String) = emitSemantic(Log.WARN, msg)
    override suspend fun e(msg: String) = emitSemantic(Log.ERROR, msg)

    private suspend fun emitSemantic(level: Int, msg: String) {
        val entries = synchronized(reducerLock) { reducer.reduce(level, msg) }
        if (entries.isEmpty()) return
        withContext(Dispatchers.Main.immediate) {
            entries.forEach { onLog(it.first, it.second) }
        }
    }
}
