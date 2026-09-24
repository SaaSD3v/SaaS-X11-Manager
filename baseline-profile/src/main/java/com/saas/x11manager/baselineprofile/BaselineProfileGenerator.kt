package com.saas.x11manager.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE_NAME = "com.saas.x11manager"

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
    }

    @Test
    fun primaryNavigation() = rule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = false
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()

        listOf("Display", "VNC", "Requirements", "Config", "Home").forEach { title ->
            if (device.wait(Until.hasObject(By.text(title)), 5_000)) {
                device.findObject(By.text(title))?.click()
                device.waitForIdle()
            }
        }
    }
}
