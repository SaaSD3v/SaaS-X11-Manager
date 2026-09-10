package com.saas.x11manager.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.saas.x11manager.MainActivity
import com.saas.x11manager.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Owns the embedded PulseAudio child in a dedicated :audio Android process.
 * The child runs as the X11 Manager app UID from nativeLibraryDir; no external
 * terminal app, foreign UID, writable executable staging or OEM preload is used.
 */
class NativeAudioRuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pulseProcess: Process? = null
    private var backend: String? = null
    private var idleChecks = 0
    private var supervisorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        supervisorJob = scope.launch {
            while (isActive) {
                delay(CONSUMER_POLL_MS)
                val process = pulseProcess
                if (process != null && !process.isAlive) {
                    pulseProcess = null
                    backend = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }

                val count = try { NativeAudioRuntime.pruneConsumers(this@NativeAudioRuntimeService) } catch (_: Exception) { -1 }
                if (count > 0) {
                    idleChecks = 0
                } else if (count == 0) {
                    idleChecks++
                    if (idleChecks >= IDLE_CHECK_LIMIT) {
                        stopCore()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, notification("Starting native audio"))
                val requested = intent?.getStringExtra(EXTRA_BACKEND) ?: BACKEND_AAUDIO
                if (pulseProcess?.isAlive != true) startCore(requested)
            }
            ACTION_RESTART -> {
                startForeground(NOTIFICATION_ID, notification("Restarting native audio"))
                val requested = intent?.getStringExtra(EXTRA_BACKEND) ?: BACKEND_AAUDIO
                stopCore()
                startCore(requested)
            }
            ACTION_STOP_IF_IDLE -> {
                startForeground(NOTIFICATION_ID, notification("Checking native audio consumers"))
                scope.launch {
                    val count = NativeAudioRuntime.pruneConsumers(this@NativeAudioRuntimeService)
                    if (count == 0) {
                        stopCore()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                }
            }
            ACTION_STOP_FORCE -> {
                startForeground(NOTIFICATION_ID, notification("Stopping native audio"))
                stopCore()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startCore(requestedBackend: String) {
        if (!NativeAudioRuntime.prepareStateForService(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val layout = NativeAudioLayout.from(this)
        val pulse = File(layout.nativeLibDir, PULSE_EXEC)
        if (!pulse.isFile) {
            layout.logFile.parentFile?.mkdirs()
            layout.logFile.appendText("Embedded PulseAudio executable missing: ${pulse.absolutePath}\n")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val module = when (requestedBackend) {
            BACKEND_SLES -> "module-sles-sink sink_name=OpenSL_ES_sink"
            else -> "module-aaudio-sink sink_name=AAudio_sink"
        }
        backend = requestedBackend
        idleChecks = 0
        layout.controlSocket.delete()
        layout.logFile.parentFile?.mkdirs()

        val command = listOf(
            pulse.absolutePath,
            "-n",
            "--daemonize=no",
            "--exit-idle-time=-1",
            "--use-pid-file=false",
            "--dl-search-path=${layout.nativeLibDir.absolutePath}",
            "-L", module,
            "-L", "module-native-protocol-unix socket=${layout.controlSocket.absolutePath} auth-cookie=${layout.cookie.absolutePath}",
        )

        pulseProcess = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(layout.logFile))
                .apply { environment().putAll(NativeAudioRuntime.nativeEnvironment(this@NativeAudioRuntimeService)) }
                .start()
                .also {
                    val label = if (requestedBackend == BACKEND_SLES) "OpenSL ES" else "AAudio"
                    updateNotification("Native audio active · $label")
                    layout.logFile.appendText("\n[SaaS] native runtime started backend=$label pid=${processPid(it)}\n")
                }
        } catch (e: Exception) {
            layout.logFile.appendText("[SaaS] native runtime start failed: ${e.message ?: e.javaClass.simpleName}\n")
            null
        }

        if (pulseProcess == null) {
            backend = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopCore() {
        val process = pulseProcess
        pulseProcess = null
        backend = null
        if (process != null && process.isAlive) {
            process.destroy()
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            } catch (_: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }
        try { NativeAudioLayout.from(this).controlSocket.delete() } catch (_: Exception) { }
    }

    private fun processPid(process: Process): Long = try {
        if (Build.VERSION.SDK_INT >= 26) process.pid() else -1L
    } catch (_: Throwable) {
        -1L
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Native Linux audio",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Audio output for active DroidSpaces Linux containers"
                setSound(null, null)
            }
        )
    }

    private fun notification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_operation_log)
            .setContentTitle("SaaS X11 Manager audio")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        supervisorJob?.cancel()
        stopCore()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.saas.x11manager.audio.START"
        const val ACTION_RESTART = "com.saas.x11manager.audio.RESTART"
        const val ACTION_STOP_IF_IDLE = "com.saas.x11manager.audio.STOP_IF_IDLE"
        const val ACTION_STOP_FORCE = "com.saas.x11manager.audio.STOP_FORCE"
        const val EXTRA_BACKEND = "backend"
        const val BACKEND_AAUDIO = "aaudio"
        const val BACKEND_SLES = "sles"

        private const val PULSE_EXEC = "libsaas_pulseaudio_exec.so"
        private const val CHANNEL_ID = "native_linux_audio"
        private const val NOTIFICATION_ID = 0x534141
        private const val CONSUMER_POLL_MS = 5_000L
        private const val IDLE_CHECK_LIMIT = 3
    }
}
