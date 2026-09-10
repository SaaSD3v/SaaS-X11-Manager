package com.saas.x11manager.operations

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.saas.x11manager.MainActivity
import com.saas.x11manager.R
import com.saas.x11manager.X11Application
import kotlinx.coroutines.*

object OperationNotifications {
    const val OWNER = "operation_owner"
    const val GENERATION = "operation_generation"
    const val OPEN = "com.saas.x11manager.OPEN_OPERATION_LOG"
    private const val CHANNEL = "operation_progress"

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Operation progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress and results of minimized container and monitor operations"
                setShowBadge(false)
            }
        )
    }

    fun build(context: Context, operation: LogOperation): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = OPEN
            data = Uri.parse("x11manager://logs/${Uri.encode(operation.owner.key)}/${operation.generation}")
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(OWNER, operation.owner.key)
        }
        val open = PendingIntent.getActivity(context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val closeIntent = Intent(context, OperationNotificationReceiver::class.java).apply {
            data = openIntent.data
            putExtra(OWNER, operation.owner.key)
            putExtra(GENERATION, operation.generation)
        }
        val close = PendingIntent.getBroadcast(context, 0, closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (operation.running) {
            // Only semantic action lines; connection details and passwords never enter the shade.
            operation.snapshot().logs.lastOrNull { (_, line) ->
                line.matches(Regex("\\[(INSTALL|CONTAINER|SESSION|X11|AUDIO|VNC)\\] [A-Za-z].*"))
            }?.second?.substringAfter("] ") ?: "In progress"
        } else operation.result.ifBlank { "Logs saved" }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_operation_log)
            .setContentTitle(operation.title)
            .setContentText(text)
            .setSubText(when (operation.owner.area) {
                OperationArea.MONITOR -> "Monitor ${operation.owner.target.toIntOrNull()?.plus(1) ?: operation.owner.target}"
                else -> operation.owner.target.takeUnless { it == "__all__" } ?: "All containers"
            })
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setDeleteIntent(close)
            .setOngoing(operation.running)
            .setAutoCancel(!operation.running)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(if (operation.running) NotificationCompat.CATEGORY_PROGRESS else NotificationCompat.CATEGORY_STATUS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // Package managers do not expose one reliable total across all phases.
            .setProgress(0, 0, operation.running)
            .addAction(0, "View logs", open)
            .apply { if (!operation.running) addAction(0, "Close", close) }
            .build()
    }

    fun post(context: Context, operation: LogOperation) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java)
            .notify(operation.notificationId, build(context, operation))
    }

    fun dismiss(context: Context, operation: LogOperation) {
        operation.notified = false
        context.getSystemService(NotificationManager::class.java).cancel(operation.notificationId)
    }
}

class OperationNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val owner = OperationOwner.parse(intent.getStringExtra(OperationNotifications.OWNER) ?: return) ?: return
        val generation = intent.getStringExtra(OperationNotifications.GENERATION) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val store = X11Application.instance.operationLogs
                store.awaitLoaded()
                val operation = store.get(owner)
                if (!operation.running && operation.generation == generation) {
                    OperationNotifications.dismiss(context, operation)
                }
            } finally { pending.finish() }
        }
    }
}
