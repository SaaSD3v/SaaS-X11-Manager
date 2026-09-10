package com.saas.x11manager

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.saas.x11manager.operations.OperationLogStore
import com.topjohnwu.superuser.Shell

class X11Application : Application(), ViewModelStoreOwner {
    // Operation ViewModels contain application context and state, never an Activity.
    // Their tasks survive closing/recreating the UI; the foreground service protects
    // minimized work. A fresh process restores logs, without replaying commands.
    override val viewModelStore = ViewModelStore()
    val operationLogs by lazy { OperationLogStore(this) }
    override fun onCreate() {
        super.onCreate()
        instance = this
        operationLogs
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER))
    }

    companion object {
        lateinit var instance: X11Application
            private set
    }
}
