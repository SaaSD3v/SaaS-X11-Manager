package com.saas.x11manager.operations

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.saas.x11manager.MainActivity
import com.saas.x11manager.X11Application
import com.saas.x11manager.util.ViewModelLogger
import com.saas.x11manager.util.VncConnectionGuide
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = X11Application::class, sdk = [26, 34])
@LooperMode(LooperMode.Mode.PAUSED)
class OperationNotificationsTest {
    private lateinit var app: X11Application
    private val store get() = app.operationLogs
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as X11Application
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OperationNotifications.createChannel(app)
    }

    private fun operation(area: OperationArea, target: String): LogOperation =
        store.get(OperationOwner(area, target)).apply { begin("Working on $target") }

    private fun advance() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300)) }

    @Test fun runningNotificationHasProgressAndCompletionUnlocksCloseForEveryScreen() {
        OperationArea.entries.forEach { area ->
            val operation = operation(area, "1")
            val running = OperationNotifications.build(app, operation)
            assertTrue(running.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
            assertTrue(running.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertFalse(running.actions.any { it.title == "Close" })
            operation.finish(false, "Verification failed")
            val finished = OperationNotifications.build(app, operation)
            assertFalse(finished.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
            assertEquals(0, finished.flags and Notification.FLAG_ONGOING_EVENT)
            assertTrue(finished.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertTrue(finished.actions.any { it.title == "Close" })
            assertEquals("Verification failed", finished.extras.getString(Notification.EXTRA_TEXT))
        }
    }

    @Test fun notificationsReturnToTheCorrectOwnerWithoutMixingContainersAndMonitors() {
        val operations = listOf(operation(OperationArea.HOME, "jellyfin"),
            operation(OperationArea.SETUP, "alpine"), operation(OperationArea.MONITOR, "1"))
        val intents = operations.map { operation ->
            shadowOf(OperationNotifications.build(app, operation).contentIntent).savedIntent.also {
                assertEquals(MainActivity::class.java.name, it.component?.className)
                assertEquals(operation.owner.key, it.getStringExtra(OperationNotifications.OWNER))
                assertEquals(OperationNotifications.OPEN, it.action)
                assertTrue(it.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
            }
        }
        assertEquals(3, intents.map { it.data }.toSet().size)
        assertEquals(3, operations.map { it.notificationId }.toSet().size)
    }

    @Test fun finishingOneMinimizedTaskKeepsTheOtherProtectedAndCloseKeepsItsLogs() {
        val first = operation(OperationArea.SETUP, "alpine")
        val second = operation(OperationArea.MONITOR, "1")
        first.notified = true
        second.notified = true
        val controller = Robolectric.buildService(LogOperationService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(app, LogOperationService::class.java)
            .putExtra(OperationNotifications.OWNER, first.owner.key), 0, 1)
        first.append(Log.INFO, "[INSTALL] ✓ Completed")
        first.finish(true, "Installed")
        advance()
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(second.notificationId, shadowOf(service).lastForegroundNotificationId)
        assertEquals(0, shadowOf(notifications).getNotification(first.notificationId).flags and Notification.FLAG_ONGOING_EVENT)
        OperationNotifications.dismiss(app, first)
        assertEquals(1, first.logs.size)
        assertNotNull(shadowOf(notifications).getNotification(second.notificationId))
        second.finish(false, "Monitor start failed")
        advance()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(shadowOf(notifications).getNotification(first.notificationId))
        val completed = shadowOf(notifications).getNotification(second.notificationId)
        assertFalse(completed.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        controller.destroy()
    }

    @Test fun completionBeforeServiceCreationDoesNotLeaveAStuckForegroundService() {
        val operation = operation(OperationArea.HOME, "jellyfin")
        operation.notified = true
        operation.finish(true, "Session started")
        val controller = Robolectric.buildService(LogOperationService::class.java).create()
        controller.get().onStartCommand(Intent(app, LogOperationService::class.java)
            .putExtra(OperationNotifications.OWNER, operation.owner.key), 0, 1)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals(0, shadowOf(notifications).getNotification(operation.notificationId).flags and Notification.FLAG_ONGOING_EVENT)
        controller.destroy()
    }

    @Test fun clearingTheActivityDoesNotCancelTheOperationViewModel() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val original = ViewModelProvider(app)[BackgroundTask::class.java]
        original.start()
        activity.pause().stop().destroy()
        advance()
        assertSame(original, ViewModelProvider(app)[BackgroundTask::class.java])
        assertTrue(original.completed)
    }

    class BackgroundTask : ViewModel() {
        var completed = false
        fun start() { viewModelScope.launch { delay(100); completed = true } }
    }

    @Test fun finalLoggerBurstIsDrainedBeforeTheDurableResultAndLiveCredentialsStayPrivate() {
        val operation = operation(OperationArea.SETUP, "alpine")
        val logger = ViewModelLogger(operation::append)
        logger.logImmediate(Log.INFO, "[+] IceWM installation completed successfully")
        runBlocking { logger.flush() }
        operation.append(Log.INFO, VncConnectionGuide.ACTIVE_SUMMARY_BEGIN)
        operation.append(Log.INFO, "[VNC] • Password: secret-for-live-view-only")
        operation.append(Log.INFO, VncConnectionGuide.ACTIVE_SUMMARY_END)
        operation.finish(true, "Installed")
        runBlocking(Dispatchers.IO) { store.awaitPersisted() }
        val archived = app.noBackupFilesDir.resolve("operation-logs").listFiles().orEmpty()
            .filter { it.extension == "log" }.map { it.inputStream().use(OperationArchive::read) }
            .single { it.owner == operation.owner }
        assertTrue(archived.logs.any { it.second.contains("IceWM installation completed successfully") })
        assertFalse(archived.logs.any { it.second.contains("secret-for-live-view-only") })
        assertTrue(operation.logs.any { it.second.contains("secret-for-live-view-only") })
        assertEquals(OperationStatus.SUCCESS, archived.status)
        assertEquals("Installed", archived.result)
    }

    @Test fun restorationCannotOverwriteANewOperationAndNewRunReplacesOldNotification() {
        val operation = operation(OperationArea.HOME, "jellyfin")
        operation.append(Log.INFO, "Old log")
        operation.finish(true, "Old result")
        val oldSnapshot = operation.snapshot()
        operation.notified = true
        OperationNotifications.post(app, operation)
        operation.begin("New start")
        operation.restore(oldSnapshot)
        assertNull(shadowOf(notifications).getNotification(operation.notificationId))
        assertEquals("New start", operation.title)
        assertTrue(operation.running)
        assertTrue(operation.logs.isEmpty())
    }
}
