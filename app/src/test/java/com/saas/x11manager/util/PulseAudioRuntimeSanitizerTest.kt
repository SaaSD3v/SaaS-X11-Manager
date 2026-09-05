package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseAudioRuntimeSanitizerTest {

    @Test
    fun currentGenerationIsReusable() {
        assertTrue(
            PulseAudioRuntimeSanitizer.generationMatches(
                PulseAudioRuntimeSanitizer.RUNTIME_GENERATION
            )
        )
    }

    @Test
    fun missingOrOldGenerationForcesRefresh() {
        assertFalse(PulseAudioRuntimeSanitizer.generationMatches(null))
        assertFalse(PulseAudioRuntimeSanitizer.generationMatches("legacy-manager-audio"))
    }
}
