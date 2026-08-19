package com.saas.x11manager

import android.app.Application
import com.topjohnwu.superuser.Shell

class X11Application : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER))
    }

    companion object {
        lateinit var instance: X11Application
            private set
    }
}
