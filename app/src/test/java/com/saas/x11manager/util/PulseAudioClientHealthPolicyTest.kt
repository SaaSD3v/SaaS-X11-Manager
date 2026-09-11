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
        assertTrue(script.contains("pacat"))
        assertTrue(script.contains("|| exit 96"))
        assertTrue(script.contains("__SAAS_AUDIO_PCM_DRAINED__"))
        assertTrue(script.contains("timeout 1 pactl info"))
        assertFalse(script.contains("selected_try"))
        assertFalse(script.contains("root_try"))
        assertFalse(script.contains("exit 95"))

        val pcm = script.indexOf("__SAAS_AUDIO_PCM_DRAINED__")
        val userDiagnostic = script.indexOf("desktop-user-control-probe")
        val rootDiagnostic = script.indexOf("root-control-probe")
        assertTrue(pcm >= 0 && userDiagnostic > pcm && rootDiagnostic > pcm)
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
