package com.saas.x11manager.ui.screen

import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManagedX11WindowPolicyTest {

    private fun source(relativePath: String): String {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate.readText()
            current = current.parentFile
        }
        error("Could not locate source file: $relativePath")
    }

    @Test
    fun orientationValuesMapToAndroidWindowOrientations() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ManagedX11WindowPolicy.requestedOrientation("auto")
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ManagedX11WindowPolicy.requestedOrientation("portrait")
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ManagedX11WindowPolicy.requestedOrientation("landscape")
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ManagedX11WindowPolicy.requestedOrientation("reverse portrait")
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ManagedX11WindowPolicy.requestedOrientation("reverse landscape")
        )
    }

    @Test
    fun cutoutPreferenceControlsWhetherFullscreenUsesTheCutoutArea() {
        assertNull(ManagedX11WindowPolicy.cutoutMode(true, Build.VERSION_CODES.O))
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER,
            ManagedX11WindowPolicy.cutoutMode(false, Build.VERSION_CODES.P)
        )
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            ManagedX11WindowPolicy.cutoutMode(true, Build.VERSION_CODES.P)
        )
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
            ManagedX11WindowPolicy.cutoutMode(true, Build.VERSION_CODES.R)
        )
    }

    @Test
    fun idleTimeoutExtendsAndroidTimeoutOnlyForTheRequestedRemainder() {
        assertNull(ManagedX11WindowPolicy.keepScreenOnWindowMillis("system", 30_000L))
        assertEquals(
            Long.MAX_VALUE,
            ManagedX11WindowPolicy.keepScreenOnWindowMillis("never", 30_000L)
        )
        assertEquals(
            30_000L,
            ManagedX11WindowPolicy.keepScreenOnWindowMillis("1", 30_000L)
        )
        assertEquals(
            0L,
            ManagedX11WindowPolicy.keepScreenOnWindowMillis("1", 120_000L)
        )
        assertNull(ManagedX11WindowPolicy.keepScreenOnWindowMillis("garbage", 30_000L))
    }

    @Test
    fun managedScreenOwnsWindowPreferencesAndRestoresManagerWindowState() {
        val screen = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/ManagedDisplayScreen.kt"
        )
        val dialogs = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/DisplayDialogs.kt"
        )
        val effects = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/ManagedX11WindowEffects.kt"
        )
        val manifest = source("app/src/main/AndroidManifest.xml")

        assertTrue(screen.contains("ManagedX11WindowEffects("))
        assertTrue(dialogs.contains("SwitchSetting(\"Use display cutout area\", useDisplayCutoutArea)"))
        assertTrue(dialogs.contains("putBoolean(\"hideCutout\", it)"))
        assertTrue(effects.contains("activity.requestedOrientation = requested"))
        assertTrue(effects.contains("Settings.System.SCREEN_OFF_TIMEOUT"))
        assertTrue(effects.contains("ManagedX11WindowPolicy"))
        assertTrue(effects.contains(".cutoutMode("))
        assertTrue(effects.contains("originalRequestedOrientation"))
        assertTrue(effects.contains("originalKeepScreenOn"))
        assertTrue(effects.contains("onDispose"))
        assertTrue(manifest.contains("android:configChanges=\"orientation|screenSize|smallestScreenSize|keyboard|keyboardHidden\""))
    }
}
