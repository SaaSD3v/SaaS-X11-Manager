package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ContainerRuntimeParserTest {

    @Test
    fun parsesMachineReadableShowAndStopsMissingRequestedContainers() {
        val states = ContainerRuntimeParser.parseMachineReadableShow(
            lines = listOf(
                "TOTAL_CONTAINERS=2",
                "RUN_CONTAINERS=1",
                "CONT_alpha=321"
            ),
            containerNames = listOf("alpha", "beta")
        )

        assertNotNull(states)
        assertEquals(ContainerStatus.RUNNING, states?.get("alpha")?.status)
        assertEquals(321, states?.get("alpha")?.pid)
        assertEquals(ContainerStatus.STOPPED, states?.get("beta")?.status)
        assertEquals(null, states?.get("beta")?.pid)
    }

    @Test
    fun malformedMachineEntryWithoutValidMarkersIsNotRecognized() {
        val states = ContainerRuntimeParser.parseMachineReadableShow(
            lines = listOf("CONT_alpha=not-a-pid"),
            containerNames = listOf("alpha")
        )

        assertNull(states)
    }

    @Test
    fun validMachineOutputForAnotherContainerStillEstablishesStoppedBaseline() {
        val states = ContainerRuntimeParser.parseMachineReadableShow(
            lines = listOf("CONT_other=777"),
            containerNames = listOf("alpha", "beta")
        )

        assertNotNull(states)
        assertEquals(ContainerStatus.STOPPED, states?.get("alpha")?.status)
        assertEquals(ContainerStatus.STOPPED, states?.get("beta")?.status)
    }

    @Test
    fun parsesPlainUnicodeTableRows() {
        val states = ContainerRuntimeParser.parsePlainShow(
            lines = listOf(
                "│ NAME │ PID │",
                "│ alpha │ 1234 │"
            ),
            containerNames = listOf("alpha", "beta")
        )

        assertNotNull(states)
        assertEquals(ContainerStatus.RUNNING, states?.get("alpha")?.status)
        assertEquals(1234, states?.get("alpha")?.pid)
        assertEquals(ContainerStatus.STOPPED, states?.get("beta")?.status)
    }

    @Test
    fun parsesPlainAsciiTableRows() {
        val states = ContainerRuntimeParser.parsePlainShow(
            lines = listOf(
                "| NAME | PID |",
                "| beta | 654 |"
            ),
            containerNames = listOf("alpha", "beta")
        )

        assertNotNull(states)
        assertEquals(ContainerStatus.STOPPED, states?.get("alpha")?.status)
        assertEquals(ContainerStatus.RUNNING, states?.get("beta")?.status)
        assertEquals(654, states?.get("beta")?.pid)
    }

    @Test
    fun unknownPlainOutputIsNotRecognized() {
        val states = ContainerRuntimeParser.parsePlainShow(
            lines = listOf("unexpected status output"),
            containerNames = listOf("alpha")
        )

        assertNull(states)
    }

    @Test
    fun explicitNoRunningContainersMessageCreatesStoppedBaseline() {
        val states = ContainerRuntimeParser.parsePlainShow(
            lines = listOf("No containers running"),
            containerNames = listOf("alpha", "beta")
        )

        assertNotNull(states)
        assertEquals(ContainerStatus.STOPPED, states?.get("alpha")?.status)
        assertEquals(ContainerStatus.STOPPED, states?.get("beta")?.status)
    }

    @Test
    fun parsesPositivePidAsRunning() {
        val state = ContainerRuntimeParser.parsePid(listOf(" 4321 "))

        assertEquals(ContainerStatus.RUNNING, state.status)
        assertEquals(4321, state.pid)
    }

    @Test
    fun parsesNoneAsStopped() {
        val state = ContainerRuntimeParser.parsePid(listOf("NONE"))

        assertEquals(ContainerStatus.STOPPED, state.status)
        assertEquals(null, state.pid)
    }

    @Test
    fun unknownPidOutputRemainsUnknown() {
        val state = ContainerRuntimeParser.parsePid(listOf("unsupported command"))

        assertEquals(ContainerStatus.UNKNOWN, state.status)
        assertEquals(null, state.pid)
    }

    @Test
    fun pidBatchPreservesRunningStoppedAndUnknownSemantics() {
        val marker = "@@PID@@"
        val states = ContainerRuntimeParser.parsePidBatch(
            lines = listOf(
                "${marker}alpha",
                "4321",
                "${marker}beta",
                "NONE",
                "${marker}gamma",
                "unsupported command"
            ),
            containerNames = listOf("alpha", "beta", "gamma"),
            marker = marker
        )

        assertEquals(ContainerStatus.RUNNING, states.getValue("alpha").status)
        assertEquals(4321, states.getValue("alpha").pid)
        assertEquals(ContainerStatus.STOPPED, states.getValue("beta").status)
        assertEquals(ContainerStatus.UNKNOWN, states.getValue("gamma").status)
    }

    @Test
    fun pidBatchMissingSectionRemainsUnknown() {
        val marker = "@@PID@@"
        val states = ContainerRuntimeParser.parsePidBatch(
            lines = listOf("${marker}alpha", "123"),
            containerNames = listOf("alpha", "beta"),
            marker = marker
        )

        assertEquals(ContainerStatus.RUNNING, states.getValue("alpha").status)
        assertEquals(ContainerStatus.UNKNOWN, states.getValue("beta").status)
    }
}
