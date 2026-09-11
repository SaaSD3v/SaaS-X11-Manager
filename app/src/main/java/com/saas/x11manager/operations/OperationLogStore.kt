package com.saas.x11manager.operations

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.saas.x11manager.util.VncConnectionGuide
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class LogOperation internal constructor(val owner: OperationOwner, private val store: OperationLogStore) {
    val logs = mutableStateListOf<Pair<Int, String>>()
    var generation by mutableStateOf(""); private set
    var title by mutableStateOf("Logs: ${owner.target}"); private set
    var status by mutableStateOf(OperationStatus.IDLE); private set
    var result by mutableStateOf(""); private set
    var detail by mutableStateOf(""); private set
    var updatedAt by mutableStateOf(0L); private set
    var notified = false
    // Stable after process recreation, including the Close action of a saved notification.
    val notificationId = (MessageDigest.getInstance("SHA-256").digest(owner.key.toByteArray())
        .take(4).fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 255) }
        and 0x3fffffff) + 4100
    val running get() = status == OperationStatus.RUNNING
    val available get() = generation.isNotEmpty() || logs.isNotEmpty()
    private var dirty = false

    fun begin(title: String, detail: String = "", initialLogs: List<Pair<Int, String>> = emptyList()) {
        check(!running) { "Operation is already running" }
        OperationNotifications.dismiss(store.context, this)
        generation = UUID.randomUUID().toString()
        this.title = title
        this.detail = detail
        result = ""
        status = OperationStatus.RUNNING
        logs.clear()
        logs.addAll(initialLogs)
        changed(immediate = true)
    }

    fun append(level: Int, line: String) {
        logs.add(level to line)
        if (logs.size > OperationArchive.MAX_ENTRIES) {
            val pinned = VncConnectionGuide.retainPinnedSummary(logs)
            val recent = logs.takeLast(OperationArchive.MAX_ENTRIES - pinned.size)
            logs.clear()
            logs.addAll(pinned)
            logs.addAll(recent.filterNot { it in pinned })
        }
        changed()
    }

    fun finish(success: Boolean, message: String) {
        status = if (success) OperationStatus.SUCCESS else OperationStatus.FAILURE
        result = message
        changed(immediate = true)
    }

    /**
     * Completes the operation and waits until every log write queued before the
     * completion marker has reached AtomicFile. Callers should use this before
     * releasing lifecycle ownership so a process death cannot turn a completed
     * operation back into a stale RUNNING snapshot on disk.
     */
    suspend fun finishDurably(success: Boolean, message: String) {
        finish(success, message)
        store.awaitPersisted()
    }

    fun changed(immediate: Boolean = false) {
        dirty = true
        updatedAt = System.currentTimeMillis()
        store.changed(this, immediate)
    }

    internal fun restore(snapshot: OperationSnapshot) {
        if (dirty) return // A new user action always wins a race with disk loading.
        generation = snapshot.generation
        title = snapshot.title
        status = snapshot.status
        result = snapshot.result
        detail = snapshot.detail
        updatedAt = snapshot.updatedAt
        logs.clear()
        logs.addAll(snapshot.logs)
    }

    internal fun snapshot(): OperationSnapshot {
        // Preserve the existing contract: live VNC credentials stay in memory only.
        var pinned = false
        val archived = logs.filter { (_, line) ->
            when (line) {
                VncConnectionGuide.ACTIVE_SUMMARY_BEGIN -> { pinned = true; false }
                VncConnectionGuide.ACTIVE_SUMMARY_END -> { pinned = false; false }
                else -> !pinned
            }
        }
        return OperationSnapshot(owner, generation, title, status, result, detail, archived, updatedAt)
    }
}

data class OpenLogRequest(val owner: OperationOwner, val token: String = UUID.randomUUID().toString())

/** Main-thread state, private durable logs and foreground ownership shared by every log screen. */
class OperationLogStore(internal val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val records = mutableStateMapOf<String, LogOperation>()
    private val directory = File(context.noBackupFilesDir, "operation-logs")
    private data class ArchiveWrite(val snapshot: OperationSnapshot?, val done: CompletableDeferred<Unit>? = null)
    private val writes = Channel<ArchiveWrite>(Channel.UNLIMITED)
    private val pendingWrites = mutableMapOf<String, Job>()
    private val loaded = CompletableDeferred<Unit>()
    internal var observer: (() -> Unit)? = null
    var openRequest by mutableStateOf<OpenLogRequest?>(null); private set

    init {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                directory.listFiles().orEmpty()
                    .filter { it.name.endsWith(".log") || it.name.endsWith(".log.bak") }
                    .map { File(it.path.removeSuffix(".bak")) }.distinct().mapNotNull { file ->
                    runCatching { AtomicFile(file).openRead().use(OperationArchive::read).recovered() }
                        .getOrNull()
                }
            }
            saved.forEach { get(it.owner).restore(it) }
            loaded.complete(Unit)
        }
        scope.launch(Dispatchers.IO) {
            for (write in writes) {
                val snapshot = write.snapshot
                if (snapshot == null) {
                    write.done?.complete(Unit)
                    continue
                }
                val file = AtomicFile(fileFor(snapshot.owner))
                var stream: java.io.FileOutputStream? = null
                try {
                    directory.mkdirs()
                    stream = file.startWrite()
                    OperationArchive.write(snapshot, stream)
                    file.finishWrite(stream)
                } catch (error: Exception) {
                    stream?.let(file::failWrite)
                    Log.e("OperationLogs", "Could not save operation logs", error)
                }
            }
        }
    }

    suspend fun awaitLoaded() = loaded.await()
    fun get(owner: OperationOwner): LogOperation = records.getOrPut(owner.key) { LogOperation(owner, this) }
    fun requestOpen(owner: OperationOwner) { openRequest = OpenLogRequest(owner) }
    fun consumeOpen(request: OpenLogRequest) { if (openRequest == request) openRequest = null }

    internal fun changed(operation: LogOperation, immediate: Boolean) {
        observer?.invoke()
        pendingWrites.remove(operation.owner.key)?.cancel()
        if (immediate) writes.trySend(ArchiveWrite(operation.snapshot()))
        else pendingWrites[operation.owner.key] = scope.launch {
            delay(500)
            writes.send(ArchiveWrite(operation.snapshot()))
            pendingWrites.remove(operation.owner.key)
        }
    }

    internal suspend fun awaitPersisted() {
        val done = CompletableDeferred<Unit>()
        writes.send(ArchiveWrite(null, done))
        done.await()
    }

    fun minimize(operation: LogOperation): Boolean {
        operation.notified = true
        return try {
            OperationNotifications.createChannel(context)
            if (operation.running) {
                // Called only from the visible minus button, satisfying FGS start restrictions.
                ContextCompat.startForegroundService(context,
                    Intent(context, LogOperationService::class.java)
                        .putExtra(OperationNotifications.OWNER, operation.owner.key)
                        .putExtra(OperationNotifications.GENERATION, operation.generation))
            } else OperationNotifications.post(context, operation)
            true
        } catch (error: RuntimeException) {
            operation.notified = false
            Toast.makeText(context, "Could not minimize this operation. Keep the logs open and retry.", Toast.LENGTH_LONG).show()
            Log.e("OperationLogs", "Foreground operation could not start", error)
            false
        }
    }

    private fun fileFor(owner: OperationOwner): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(owner.key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.log")
    }
}
