package dev.herdr.mobile.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup profile: launch the app and wait for the Home list, which pulls in
 * the Compose/DataStore/OkHttp classes that dominate cold start.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = "dev.herdr.mobile") {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Herdr")), 10_000)
        device.waitForIdle()
    }
}
