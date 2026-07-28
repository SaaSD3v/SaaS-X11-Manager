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

class ViewModelLogger(
    private val onLog: (Int, String) -> Unit
) : ContainerLogger() {
    override fun logImmediate(level: Int, msg: String) {
        onLog(level, msg)
    }
}
