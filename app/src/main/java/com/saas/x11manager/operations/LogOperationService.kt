package com.saas.x11manager.operations

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.saas.x11manager.X11Application
import kotlinx.coroutines.*

/** Protects user-minimized work; owns no X11 server or container lifecycle. */
class LogOperationService : Service() {
    private val store get() = X11Application.instance.operationLogs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updateJob: Job? = null
    private var foregroundId: Int? = null

    override fun onCreate() {
        super.onCreate()
        OperationNotifications.createChannel(this)
        store.observer = {
            if (updateJob?.isActive != true) updateJob = scope.launch {
                delay(250) // Coalesce output bursts without losing the completion transition.
                refreshNotifications()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val owner = OperationOwner.parse(intent?.getStringExtra(OperationNotifications.OWNER).orEmpty())
        val operation = owner?.let(store::get)
        if (operation == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Even a task that finished between the click and service creation must satisfy
        // startForegroundService's deadline before detaching its completed notification.
        promote(operation)
        refreshNotifications()
        return START_NOT_STICKY
    }

    private fun promote(operation: LogOperation) {
        if (foregroundId != null && foregroundId != operation.notificationId) {
            stopForeground(STOP_FOREGROUND_DETACH)
        }
        val notification = OperationNotifications.build(this, operation)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(operation.notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(operation.notificationId, notification)
        foregroundId = operation.notificationId
    }

    private fun refreshNotifications() {
        val visible = store.records.values.filter { it.notified }
        val running = visible.firstOrNull { it.running }
        if (running == null) {
            stopForeground(STOP_FOREGROUND_DETACH)
            foregroundId = null
        } else if (foregroundId != running.notificationId) promote(running)

        visible.forEach { OperationNotifications.post(this, it) }
        if (running == null) stopSelf()
    }

    override fun onDestroy() {
        store.observer = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
