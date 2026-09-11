package com.saas.x11manager.operations

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

enum class OperationArea { HOME, SETUP, MONITOR, VNC }

data class OperationOwner(val area: OperationArea, val target: String) {
    val key: String get() = "${area.name}:$target"
    companion object {
        fun parse(key: String): OperationOwner? = runCatching {
            OperationOwner(OperationArea.valueOf(key.substringBefore(':')), key.substringAfter(':'))
                .takeIf { it.target.isNotBlank() && ':' in key }
        }.getOrNull()
    }
}

enum class OperationStatus { IDLE, RUNNING, SUCCESS, FAILURE, INTERRUPTED }

data class OperationSnapshot(
    val owner: OperationOwner,
    val generation: String,
    val title: String,
    val status: OperationStatus,
    val result: String,
    val detail: String,
    val logs: List<Pair<Int, String>>,
    val updatedAt: Long
) {
    /** A killed process cannot prove that its external command completed. Never replay it. */
    fun recovered(): OperationSnapshot = if (status == OperationStatus.RUNNING) copy(
        status = OperationStatus.INTERRUPTED,
        result = "Interrupted — check the container state before retrying",
        logs = (logs + (5 to "[SESSION] ! App execution was interrupted; completion was not confirmed"))
            .takeLast(OperationArchive.MAX_ENTRIES)
    ) else this
}

/** Small, versioned private archive. Notification text never contains command output or credentials. */
object OperationArchive {
    const val MAX_ENTRIES = 1500
    private const val VERSION = 1

    fun write(snapshot: OperationSnapshot, output: OutputStream) {
        DataOutputStream(output).apply {
            writeInt(VERSION)
            writeUTF(snapshot.owner.key)
            writeUTF(snapshot.generation)
            writeUTF(snapshot.title.take(1000))
            writeUTF(snapshot.status.name)
            writeUTF(snapshot.result.take(2000))
            writeUTF(snapshot.detail.take(1000))
            writeLong(snapshot.updatedAt)
            writeInt(snapshot.logs.size.coerceAtMost(MAX_ENTRIES))
            snapshot.logs.takeLast(MAX_ENTRIES).forEach { (level, line) ->
                writeInt(level)
                writeUTF(line.take(8000))
            }
            flush()
        }
    }

    fun read(input: InputStream): OperationSnapshot = DataInputStream(input).run {
        require(readInt() == VERSION) { "Unsupported log archive" }
        val owner = requireNotNull(OperationOwner.parse(readUTF()))
        val generation = readUTF()
        val title = readUTF()
        val status = OperationStatus.valueOf(readUTF())
        val result = readUTF()
        val detail = readUTF()
        val time = readLong()
        val count = readInt().also { require(it in 0..MAX_ENTRIES) }
        OperationSnapshot(
            owner,
            generation,
            title,
            status,
            result,
            detail,
            List(count) { readInt() to readUTF() },
            time
        )
    }
}
