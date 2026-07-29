package com.saas.x11manager

import android.app.Application
import android.util.Log
import com.topjohnwu.superuser.Shell

class X11Application : Application() {
    override fun onCreate() {
        super.onCreate()
        Shell.enableVerboseLogging = true
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
        )
        Log.d("X11App", "Shell initialized, FLAG_MOUNT_MASTER set")
    }
}
