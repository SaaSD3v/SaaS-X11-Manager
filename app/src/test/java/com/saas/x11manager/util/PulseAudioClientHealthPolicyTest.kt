package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseAudioClientHealthPolicyTest {
    @Test
    fun `control plane probes are advisory but PCM remains authoritative`() {
        val script = PulseAudioClientConfig.install("tcp:172.28.0.1:4713")

        assertTrue(script.contains("desktop-user-control-probe"))
        assertTrue(script.contains("root-control-probe"))
        assertTrue(script.contains("selected_try"))
        assertTrue(script.contains("root_try"))
        assertTrue(script.contains("pacat"))
        assertTrue(script.contains("|| exit 96"))
        assertTrue(script.contains("__SAAS_AUDIO_PCM_DRAINED__"))
        assertFalse(script.contains("exit 95"))
    }

    @Test
    fun `pcm failure is reported as playback failure`() {
        val summary = PulseAudioClientConfig.failureSummary(
            96,
            listOf("__SAAS_AUDIO_FAILURE__:pcm-playback:96")
        )
        assertTrue(summary.contains("PCM playback"))
        assertTrue(summary.contains("exit 96"))
    }
}
