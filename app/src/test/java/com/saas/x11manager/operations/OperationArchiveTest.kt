package com.saas.x11manager.operations

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class OperationArchiveTest {
    private fun snapshot(area: OperationArea = OperationArea.SETUP) = OperationSnapshot(
        OperationOwner(area, "alpine:desktop"), "generation-1", "Installing IceWM",
        OperationStatus.SUCCESS, "OK: IceWM installed", "ICEWM",
        listOf(4 to "[INSTALL] ✓ IceWM setup completed", 5 to "[CONTAINER] ! warning"), 12345L
    )

    @Test fun preservesTheOwnerResultAndOrderedUnicodeLogs() {
        OperationArea.entries.forEach { area ->
            val original = snapshot(area)
            val bytes = ByteArrayOutputStream().also { OperationArchive.write(original, it) }.toByteArray()
            assertEquals(original, OperationArchive.read(ByteArrayInputStream(bytes)))
            assertEquals(original.owner, OperationOwner.parse(original.owner.key))
        }
    }

    @Test fun killedWorkIsRecoveredAsUnconfirmedAndNeverAsSuccess() {
        val running = snapshot().copy(status = OperationStatus.RUNNING, result = "")
        val recovered = running.recovered()
        assertEquals(OperationStatus.INTERRUPTED, recovered.status)
        assertTrue(recovered.result.contains("check the container state"))
        assertEquals(running.logs, recovered.logs.dropLast(1))
        assertEquals(recovered, recovered.recovered())
        assertEquals(snapshot(), snapshot().recovered())
    }

    @Test fun oversizedStreamsKeepTheRecentTailAndFinalResult() {
        val original = snapshot().copy(logs = List(4000) { 4 to "Line $it" })
        val bytes = ByteArrayOutputStream().also { OperationArchive.write(original, it) }.toByteArray()
        val restored = OperationArchive.read(ByteArrayInputStream(bytes))
        assertEquals(OperationArchive.MAX_ENTRIES, restored.logs.size)
        assertEquals("Line 3999", restored.logs.last().second)
        assertEquals(original.result, restored.result)
    }

    @Test fun corruptOrUnsupportedFilesCannotBecomeOperationResults() {
        val unsupported = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(999) }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { OperationArchive.read(ByteArrayInputStream(unsupported)) }
        assertThrows(java.io.EOFException::class.java) { OperationArchive.read(ByteArrayInputStream(byteArrayOf())) }
        assertNull(OperationOwner.parse("untrusted:target"))
        assertNull(OperationOwner.parse("HOME:"))
    }
}
