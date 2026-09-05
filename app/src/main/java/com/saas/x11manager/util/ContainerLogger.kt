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
 * details mode. Keeping logImmediate filtered (rather than dropping it entirely)
 * is important because some Manager operations report their useful lifecycle
 * events through callback streams.
 */
class ViewModelLogger(
    private val onLog: (Int, String) -> Unit
) : ContainerLogger() {
    private val reducer = ConciseLogReducer()
    private val reducerLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun logImmediate(level: Int, msg: String) {
        val entries = reduce(level, msg)
        if (entries.isEmpty()) return

        if (Looper.myLooper() == Looper.getMainLooper()) {
            entries.forEach { onLog(it.first, it.second) }
        } else {
            mainHandler.post {
                entries.forEach { onLog(it.first, it.second) }
            }
        }
    }

    override suspend fun i(msg: String) = emitSemantic(Log.INFO, msg)
    override suspend fun w(msg: String) = emitSemantic(Log.WARN, msg)
    override suspend fun e(msg: String) = emitSemantic(Log.ERROR, msg)

    private suspend fun emitSemantic(level: Int, msg: String) {
        val entries = reduce(level, msg)
        if (entries.isEmpty()) return
        withContext(Dispatchers.Main.immediate) {
            entries.forEach { onLog(it.first, it.second) }
        }
    }

    private fun reduce(level: Int, msg: String): List<Pair<Int, String>> =
        synchronized(reducerLock) { reducer.reduce(level, msg) }
}
